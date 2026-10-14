package com.bento.crm.invoice.service;

import com.bento.crm.organization.model.Organization;
import com.bento.crm.organization.repository.OrganizationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * "Today" for billing purposes, in the organization's own time zone, so an invoice due on the
 * 15th is not reminded about at 23:00 on the 14th in Casablanca because the server runs on UTC.
 */
@Component
public class BillingCalendar {

    private final OrganizationRepository organizationRepository;
    private final ZoneId defaultZone;
    private final Clock clock;

    @Autowired
    public BillingCalendar(OrganizationRepository organizationRepository,
                           @Value("${crm.billing.default-zone:Africa/Casablanca}") String defaultZone) {
        this(organizationRepository, ZoneId.of(defaultZone), Clock.systemUTC());
    }

    BillingCalendar(OrganizationRepository organizationRepository, ZoneId defaultZone, Clock clock) {
        this.organizationRepository = organizationRepository;
        this.defaultZone = defaultZone;
        this.clock = clock;
    }

    public LocalDate today(UUID organizationId) {
        return LocalDate.now(clock.withZone(zoneOf(organizationId)));
    }

    private ZoneId zoneOf(UUID organizationId) {
        String tz = organizationRepository.findById(organizationId).map(Organization::getTimezone).orElse(null);
        if (tz == null || tz.isBlank()) {
            return defaultZone;
        }
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException e) {
            return defaultZone;
        }
    }
}
