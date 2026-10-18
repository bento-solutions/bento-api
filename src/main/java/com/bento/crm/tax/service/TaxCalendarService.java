package com.bento.crm.tax.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.security.CurrentUser;
import com.bento.crm.notification.service.TeamNotifier;
import com.bento.crm.partner.repository.PartnerRepository;
import com.bento.crm.tax.dto.TaxDtos;
import com.bento.crm.tax.model.TaxDeadline;
import com.bento.crm.tax.model.TaxProfile;
import com.bento.crm.tax.repository.TaxDeadlineRepository;
import com.bento.crm.tax.repository.TaxProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The tax calendar: each profile (the company itself, or a client) gets its TVA, IS, IR and CNSS
 * deadlines for the coming year, and managers are reminded a week before each one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaxCalendarService {

    static final int HORIZON_DAYS = 400;
    static final int REMIND_DAYS = 7;

    private final TaxProfileRepository profileRepository;
    private final TaxDeadlineRepository deadlineRepository;
    private final PartnerRepository partnerRepository;
    private final TeamNotifier teamNotifier;
    private final JdbcTemplate jdbc;

    private static UUID org() {
        return TenantContext.getCurrentOrganizationId();
    }

    public List<TaxDtos.Profile> profiles() {
        return jdbc.query("""
                SELECT t.*, p.name AS partner_name FROM tax_profile t LEFT JOIN partner p ON p.id = t.partner_id
                WHERE t.organization_id = ? AND t.deleted_at IS NULL ORDER BY t.partner_id NULLS FIRST, p.name
                """, TaxCalendarService::profile, org());
    }

    @Transactional
    public TaxDtos.Profile saveProfile(UUID id, TaxDtos.ProfileRequest r) {
        UUID orgId = org();
        TaxProfile p;
        if (id == null) {
            p = new TaxProfile();
            p.setOrganizationId(orgId);
            if (r.partnerId() != null) {
                partnerRepository.findByOrganizationIdAndId(orgId, r.partnerId())
                        .orElseThrow(() -> new ResourceNotFoundException("Client not found"));
                p.setPartnerId(r.partnerId());
            }
        } else {
            p = profileRepository.find(orgId, id).orElseThrow(() -> new ResourceNotFoundException("Tax profile not found"));
        }
        if (r.vatRegime() != null) p.setVatRegime(r.vatRegime());
        if (r.corporateTax() != null) p.setCorporateTax(r.corporateTax());
        if (r.hasEmployees() != null) p.setHasEmployees(r.hasEmployees());
        if (r.active() != null) p.setActive(r.active());
        if (r.fiscalYearEndMonth() != null) {
            if (r.fiscalYearEndMonth() < 1 || r.fiscalYearEndMonth() > 12) {
                throw new IllegalArgumentException("The financial year ends in a month from 1 to 12");
            }
            p.setFiscalYearEndMonth(r.fiscalYearEndMonth());
        }
        try {
            p = profileRepository.saveAndFlush(p);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException(p.getPartnerId() == null ? "The company already has a tax profile"
                    : "This client already has a tax profile");
        }
        // Future deadlines that no longer apply (regime changed) are dropped; done ones stay.
        jdbc.update("UPDATE tax_deadline SET deleted_at = now() WHERE profile_id = ? AND status = 'TODO'"
                + " AND due_date >= CURRENT_DATE AND deleted_at IS NULL", p.getId());
        if (p.isActive()) generate(p, LocalDate.now());
        UUID saved = p.getId();
        return profiles().stream().filter(x -> x.id().equals(saved)).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteProfile(UUID id) {
        TaxProfile p = profileRepository.find(org(), id).orElseThrow(() -> new ResourceNotFoundException("Tax profile not found"));
        p.setDeletedAt(Instant.now());
        profileRepository.save(p);
        jdbc.update("UPDATE tax_deadline SET deleted_at = now() WHERE profile_id = ? AND status = 'TODO' AND deleted_at IS NULL", id);
    }

    /** Adds the deadlines the profile is missing, from 60 days ago to about a year ahead. */
    int generate(TaxProfile p, LocalDate today) {
        int added = 0;
        for (TaxRules.Deadline d : TaxRules.between(p, today.minusDays(60), today.plusDays(HORIZON_DAYS))) {
            added += jdbc.update("""
                    INSERT INTO tax_deadline (organization_id, profile_id, partner_id, kind, period_label, period_start,
                                              period_end, due_date)
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM tax_deadline WHERE profile_id = ? AND kind = ? AND period_label = ?
                                      AND (deleted_at IS NULL OR status <> 'TODO'))
                    ON CONFLICT DO NOTHING
                    """, p.getOrganizationId(), p.getId(), p.getPartnerId(), d.kind().name(), d.periodLabel(),
                    d.periodStart(), d.periodEnd(), d.dueDate(), p.getId(), d.kind().name(), d.periodLabel());
        }
        return added;
    }

    public List<TaxDtos.Deadline> deadlines(LocalDate from, LocalDate to, UUID partnerId, boolean companyOnly,
                                            TaxDeadline.Status status) {
        StringBuilder sql = new StringBuilder(SELECT + " WHERE d.organization_id = ? AND d.deleted_at IS NULL AND d.due_date BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>(List.of(org(), from, to));
        if (partnerId != null) { sql.append(" AND d.partner_id = ?"); args.add(partnerId); }
        else if (companyOnly) sql.append(" AND d.partner_id IS NULL");
        if (status != null) { sql.append(" AND d.status = ?"); args.add(status.name()); }
        sql.append(" ORDER BY d.due_date, d.kind");
        return jdbc.query(sql.toString(), TaxCalendarService::deadline, args.toArray());
    }

    /** A client's own deadlines, for the portal. */
    public List<TaxDtos.Deadline> forPartner(UUID orgId, UUID partnerId, LocalDate from, LocalDate to) {
        return jdbc.query(SELECT + " WHERE d.organization_id = ? AND d.partner_id = ? AND d.deleted_at IS NULL"
                + " AND d.due_date BETWEEN ? AND ? ORDER BY d.due_date", TaxCalendarService::deadline, orgId, partnerId, from, to);
    }

    @Transactional
    public TaxDtos.Deadline update(UUID id, TaxDtos.DeadlineUpdate r) {
        TaxDeadline d = deadlineRepository.find(org(), id).orElseThrow(() -> new ResourceNotFoundException("Deadline not found"));
        if (r.status() != null && r.status() != d.getStatus()) {
            d.setStatus(r.status());
            d.setDoneAt(r.status() == TaxDeadline.Status.DONE ? Instant.now() : null);
            d.setDoneBy(r.status() == TaxDeadline.Status.DONE ? CurrentUser.idOrNull() : null);
        }
        if (r.amount() != null) d.setAmount(r.amount().signum() < 0 ? null : r.amount());
        if (r.note() != null) d.setNote(r.note().isBlank() ? null : r.note().length() > 500 ? r.note().substring(0, 500) : r.note());
        deadlineRepository.saveAndFlush(d);
        return jdbc.query(SELECT + " WHERE d.id = ?", TaxCalendarService::deadline, id).get(0);
    }

    /** Daily: tops up every calendar and reminds managers of what is due within a week. */
    @Scheduled(cron = "${app.tax.cron:0 30 6 * * *}")
    public void daily() {
        LocalDate today = LocalDate.now();
        for (TaxProfile p : profileRepository.allActive()) {
            try {
                generate(p, today);
            } catch (RuntimeException e) {
                log.warn("Tax calendar for profile {} not generated: {}", p.getId(), e.getMessage());
            }
        }
        remind(today);
    }

    int remind(LocalDate today) {
        int sent = 0;
        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        for (TaxDeadline d : deadlineRepository.dueForReminder(today.plusDays(REMIND_DAYS))) {
            if (d.getDueDate().isBefore(today.minusDays(30))) continue; // long past: not worth a ping
            String who = d.getPartnerId() == null ? "" : partnerRepository.findByOrganizationIdAndId(d.getOrganizationId(), d.getPartnerId())
                    .map(x -> " - " + x.getName()).orElse("");
            TenantContext.setCurrentOrganizationId(d.getOrganizationId());
            try {
                teamNotifier.notifyManagers(d.getOrganizationId(), label(d.getKind()) + " " + d.getPeriodLabel() + who,
                        (d.getDueDate().isBefore(today) ? "Overdue since " : "Due on ") + d.getDueDate().format(f),
                        "TAX_DEADLINE", d.getId());
                jdbc.update("UPDATE tax_deadline SET reminded_at = now() WHERE id = ?", d.getId());
                sent++;
            } finally {
                TenantContext.clear();
            }
        }
        return sent;
    }

    static String label(TaxDeadline.Kind k) {
        return switch (k) {
            case TVA -> "TVA declaration";
            case IS_INSTALLMENT -> "IS instalment";
            case IS_RETURN -> "IS return";
            case IR_SALARIES -> "IR on salaries";
            case CNSS -> "CNSS declaration";
            case IR_ANNUAL_SALARIES -> "Annual salary declaration (9421)";
        };
    }

    private static final String SELECT = """
            SELECT d.*, p.name AS partner_name FROM tax_deadline d LEFT JOIN partner p ON p.id = d.partner_id
            """;

    private static TaxDtos.Profile profile(ResultSet rs, int i) throws SQLException {
        return new TaxDtos.Profile(rs.getObject("id", UUID.class), rs.getObject("partner_id", UUID.class),
                rs.getString("partner_name"), TaxProfile.VatRegime.valueOf(rs.getString("vat_regime")),
                rs.getBoolean("corporate_tax"), rs.getInt("fiscal_year_end_month"), rs.getBoolean("has_employees"),
                rs.getBoolean("is_active"));
    }

    private static TaxDtos.Deadline deadline(ResultSet rs, int i) throws SQLException {
        LocalDate due = rs.getObject("due_date", LocalDate.class);
        TaxDeadline.Status status = TaxDeadline.Status.valueOf(rs.getString("status"));
        java.sql.Timestamp done = rs.getTimestamp("done_at");
        return new TaxDtos.Deadline(rs.getObject("id", UUID.class), rs.getObject("profile_id", UUID.class),
                rs.getObject("partner_id", UUID.class), rs.getString("partner_name"),
                TaxDeadline.Kind.valueOf(rs.getString("kind")), rs.getString("period_label"),
                rs.getObject("period_start", LocalDate.class), rs.getObject("period_end", LocalDate.class), due, status,
                done == null ? null : done.toInstant(), rs.getBigDecimal("amount"), rs.getString("note"),
                status == TaxDeadline.Status.TODO && due.isBefore(LocalDate.now()));
    }
}
