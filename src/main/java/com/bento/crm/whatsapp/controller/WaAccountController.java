package com.bento.crm.whatsapp.controller;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.identity.repository.AppUserRepository;
import com.bento.crm.whatsapp.service.WaOutreachGuard;
import com.bento.crm.whatsapp.service.WaSessionService;
import com.bento.crm.whatsapp.model.WaAccount;
import com.bento.crm.whatsapp.repository.WaAccountRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Connects an organization's WhatsApp sending number.
 */
@RestController
@RequestMapping("/whatsapp/account")
@RequiredArgsConstructor
@Tag(name = "WhatsApp Account", description = "Per-organization WhatsApp number configuration")
public class WaAccountController {

    private final WaAccountRepository accountRepository;
    private final WaSessionService sessionService;
    private final AppUserRepository userRepository;
    private final WaOutreachGuard outreachGuard;
    private final jakarta.persistence.EntityManager entityManager;

    @GetMapping
    @PreAuthorize("hasAuthority('CAMPAIGNS_READ')")
    @Operation(summary = "Current organization's WhatsApp connection")
    public ResponseEntity<WaAccountResponse> get() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return accountRepository.findByOrganizationId(orgId)
                .map(a -> ResponseEntity.ok(WaAccountResponse.from(a)))
                .orElseGet(() -> ResponseEntity.ok(null));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('CAMPAIGNS_WRITE')")
    @Operation(summary = "Connect or update the organization's WhatsApp number")
    @Transactional
    public ResponseEntity<WaAccountResponse> connect(@RequestBody ConnectRequest request) {
        UUID orgId = TenantContext.getCurrentOrganizationId();

        if (request.getProvider() == WaAccount.Provider.BAILEYS) {
            throw new IllegalArgumentException("Link a personal number through /whatsapp/account/baileys/prepare");
        }
        WaAccount account = accountRepository.findByOrganizationId(orgId).orElseGet(WaAccount::new);
        requireNoLinkedSession(account);
        account.setOrganizationId(orgId);
        account.setProvider(request.getProvider() == null ? WaAccount.Provider.MOCK : request.getProvider());
        account.setPhoneNumberId(request.getPhoneNumberId());
        account.setWabaId(request.getWabaId());
        account.setDisplayPhoneNumber(request.getDisplayPhoneNumber());
        account.setStatus(WaAccount.Status.CONNECTED);

        // Secrets are only overwritten when actually supplied, so re-saving the form
        // without retyping the token does not blank it out.
        if (request.getAccessToken() != null && !request.getAccessToken().isBlank()) {
            account.setAccessToken(request.getAccessToken());
        }
        if (request.getAppSecret() != null && !request.getAppSecret().isBlank()) {
            account.setAppSecret(request.getAppSecret());
        }
        if (request.getVerifyToken() != null && !request.getVerifyToken().isBlank()) {
            account.setVerifyToken(request.getVerifyToken());
        }

        return ResponseEntity.ok(WaAccountResponse.from(accountRepository.save(account)));
    }

    /**
     * Provisions a simulated number in one click so campaigns can be exercised
     * before a Meta account exists.
     */
    @PostMapping("/mock")
    @PreAuthorize("hasAuthority('CAMPAIGNS_WRITE')")
    @Operation(summary = "Provision a simulated WhatsApp number for testing")
    @Transactional
    public ResponseEntity<WaAccountResponse> connectMock() {
        UUID orgId = TenantContext.getCurrentOrganizationId();

        WaAccount account = accountRepository.findByOrganizationId(orgId).orElseGet(WaAccount::new);
        requireNoLinkedSession(account);
        account.setOrganizationId(orgId);
        account.setProvider(WaAccount.Provider.MOCK);
        // Derived from the org id so it stays unique across tenants, satisfying the
        // global uniqueness the webhook routing depends on.
        account.setPhoneNumberId("mock-" + orgId);
        account.setDisplayPhoneNumber("+212600000000");
        account.setStatus(WaAccount.Status.CONNECTED);

        return ResponseEntity.ok(WaAccountResponse.from(accountRepository.save(account)));
    }

    // --- Settings (all providers) ---------------------------------------------------------------

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('WHATSAPP_ADMIN')")
    @Operation(summary = "Lead creation, visibility and pacing settings")
    public ResponseEntity<SettingsDto> settings() {
        return ResponseEntity.ok(withSafety(requireAccount()));
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('WHATSAPP_ADMIN')")
    @Operation(summary = "Update lead creation, visibility and pacing settings")
    @Transactional
    public ResponseEntity<SettingsDto> updateSettings(@RequestBody SettingsDto request) {
        WaAccount account = requireAccount();
        if (request.getAutoCreateLeads() != null) {
            account.setAutoCreateLeads(request.getAutoCreateLeads());
        }
        if (request.getVisibility() != null) {
            account.setVisibility(request.getVisibility());
        }
        if (request.getDefaultAssigneeUserId() != null
                && userRepository.findByOrganizationIdAndId(account.getOrganizationId(), request.getDefaultAssigneeUserId()).isEmpty()) {
            throw new IllegalArgumentException("Unknown user for the default assignee");
        }
        account.setDefaultAssigneeUserId(request.getDefaultAssigneeUserId());
        // Bounds from docs/whatsapp-anti-spam-policy.md: looser than this and a personal number
        // behaves like a bulk sender, whatever the admin intends.
        account.setReplyMinGapSeconds(within(request.getReplyMinGapSeconds(), 5, 300, "replyMinGapSeconds"));
        account.setOutreachMinGapSeconds(within(request.getOutreachMinGapSeconds(), 60, 3600, "outreachMinGapSeconds"));
        account.setOutreachPerHour(within(request.getOutreachPerHour(), 1, 15, "outreachPerHour"));
        account.setNewChatsPerDay(within(request.getNewChatsPerDay(), 0, 25, "newChatsPerDay"));
        return ResponseEntity.ok(withSafety(accountRepository.save(account)));
    }

    @PostMapping("/outreach/pause")
    @PreAuthorize("hasAuthority('WHATSAPP_ADMIN')")
    @Operation(summary = "Stop first messages, relances and campaigns for a while; replies still go out")
    public ResponseEntity<SettingsDto> pauseOutreach(@RequestBody PauseRequest request) {
        int hours = request.getHours() == null ? 24 : request.getHours();
        if (hours < 1 || hours > 720) {
            throw new IllegalArgumentException("hours must be between 1 and 720");
        }
        outreachGuard.manualPause(TenantContext.getCurrentOrganizationId(), java.time.Duration.ofHours(hours), request.getReason());
        return ResponseEntity.ok(withSafety(freshAccount()));
    }

    @PostMapping("/outreach/resume")
    @PreAuthorize("hasAuthority('WHATSAPP_ADMIN')")
    @Operation(summary = "Lift Bento's outreach pause (WhatsApp's own restriction and the warm-up still apply)")
    public ResponseEntity<SettingsDto> resumeOutreach() {
        outreachGuard.resume(TenantContext.getCurrentOrganizationId());
        return ResponseEntity.ok(withSafety(freshAccount()));
    }

    private SettingsDto withSafety(WaAccount account) {
        SettingsDto dto = SettingsDto.from(account);
        if (account.getProvider() == WaAccount.Provider.BAILEYS) {
            dto.setSafety(outreachGuard.view(account, java.time.Instant.now()));
        }
        return dto;
    }

    // --- Linked personal number (Baileys) ------------------------------------------------------

    @PostMapping("/baileys/prepare")
    @PreAuthorize("hasAuthority('WHATSAPP_ADMIN')")
    @Operation(summary = "Use a personal number linked as a device; does not contact WhatsApp yet")
    public ResponseEntity<WaSessionService.SessionView> prepare(@RequestBody PrepareRequest request) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        sessionService.prepare(orgId, request.getPhone(), request.getAutoCreateLeads());
        return ResponseEntity.ok(sessionService.view(orgId));
    }

    @PostMapping("/baileys/link")
    @PreAuthorize("hasAuthority('WHATSAPP_ADMIN')")
    @Operation(summary = "Request a pairing code for the prepared number")
    public ResponseEntity<WaSessionService.SessionView> link() {
        return ResponseEntity.ok(sessionService.link(TenantContext.getCurrentOrganizationId()));
    }

    @PostMapping("/baileys/start")
    @PreAuthorize("hasAuthority('WHATSAPP_ADMIN')")
    @Operation(summary = "Reconnect the linked number")
    public ResponseEntity<WaSessionService.SessionView> start() {
        return ResponseEntity.ok(sessionService.start(TenantContext.getCurrentOrganizationId()));
    }

    @PostMapping("/baileys/stop")
    @PreAuthorize("hasAuthority('WHATSAPP_ADMIN')")
    @Operation(summary = "Disconnect without unlinking")
    public ResponseEntity<WaSessionService.SessionView> stop() {
        return ResponseEntity.ok(sessionService.stop(TenantContext.getCurrentOrganizationId()));
    }

    @PostMapping("/baileys/unlink")
    @PreAuthorize("hasAuthority('WHATSAPP_ADMIN')")
    @Operation(summary = "Unlink the device from the phone and forget its credentials")
    public ResponseEntity<WaSessionService.SessionView> unlink() {
        return ResponseEntity.ok(sessionService.unlink(TenantContext.getCurrentOrganizationId()));
    }

    @GetMapping("/session")
    @PreAuthorize("hasAuthority('WHATSAPP_ADMIN')")
    @Operation(summary = "State of the linked-device session (pairing code, open, logged out, …)")
    public ResponseEntity<WaSessionService.SessionView> session() {
        return ResponseEntity.ok(sessionService.view(TenantContext.getCurrentOrganizationId()));
    }

    /** Switching away from a linked phone would orphan its running bot session. */
    private static void requireNoLinkedSession(WaAccount account) {
        if (account.getProvider() == WaAccount.Provider.BAILEYS && account.getSessionState() != null
                && !java.util.Set.of("stopped", "logged_out", "needs_pairing", "pairing_failed").contains(account.getSessionState())) {
            throw new IllegalStateException("Unlink the WhatsApp number first");
        }
    }

    /**
     * The account as stored now. The guard writes with direct UPDATEs, and within one request
     * (open-session-in-view) an entity loaded earlier would still show the old values.
     */
    private WaAccount freshAccount() {
        entityManager.clear();
        return requireAccount();
    }

    private WaAccount requireAccount() {
        return accountRepository.findByOrganizationId(TenantContext.getCurrentOrganizationId())
                .orElseThrow(() -> new ResourceNotFoundException("No WhatsApp account"));
    }

    private static Integer within(Integer value, int min, int max, String field) {
        if (value != null && (value < min || value > max)) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        }
        return value;
    }

    @Data
    public static class PauseRequest {
        private Integer hours;
        private String reason;
    }

    @Data
    public static class PrepareRequest {
        private String phone;
        private WaAccount.AutoCreateLeads autoCreateLeads;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SettingsDto {
        private WaAccount.AutoCreateLeads autoCreateLeads;
        private WaAccount.Visibility visibility;
        private UUID defaultAssigneeUserId;
        private Integer replyMinGapSeconds;
        private Integer outreachMinGapSeconds;
        private Integer outreachPerHour;
        private Integer newChatsPerDay;
        /** Read-only: pause, WhatsApp's restriction and quota, warm-up, today's counts (linked numbers). */
        private WaOutreachGuard.SafetyView safety;

        static SettingsDto from(WaAccount a) {
            return SettingsDto.builder()
                    .autoCreateLeads(a.getAutoCreateLeads())
                    .visibility(a.getVisibility())
                    .defaultAssigneeUserId(a.getDefaultAssigneeUserId())
                    .replyMinGapSeconds(a.getReplyMinGapSeconds())
                    .outreachMinGapSeconds(a.getOutreachMinGapSeconds())
                    .outreachPerHour(a.getOutreachPerHour())
                    .newChatsPerDay(a.getNewChatsPerDay())
                    .build();
        }
    }

    @Data
    public static class ConnectRequest {
        private WaAccount.Provider provider;
        private String phoneNumberId;
        private String wabaId;
        private String displayPhoneNumber;
        private String accessToken;
        private String appSecret;
        private String verifyToken;
    }

    /** Secrets are never echoed back; only whether they are set. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WaAccountResponse {
        private UUID id;
        private String provider;
        private String phoneNumberId;
        private String wabaId;
        private String displayPhoneNumber;
        private String status;
        private String qualityRating;
        private boolean hasAccessToken;
        private String sessionState;
        private String linkedPhone;
        /** Outreach waits until then (Bento's pause); null when not paused. */
        private java.time.Instant outreachPausedUntil;
        /** WhatsApp forbids starting new chats until then; null when not restricted. */
        private java.time.Instant reachoutLockedUntil;

        static WaAccountResponse from(WaAccount a) {
            return WaAccountResponse.builder()
                    .id(a.getId())
                    .provider(a.getProvider().name())
                    .phoneNumberId(a.getPhoneNumberId())
                    .wabaId(a.getWabaId())
                    .displayPhoneNumber(a.getDisplayPhoneNumber())
                    .status(a.getStatus().name())
                    .qualityRating(a.getQualityRating())
                    .hasAccessToken(a.getAccessToken() != null && !a.getAccessToken().isBlank())
                    .sessionState(a.getSessionState())
                    .linkedPhone(a.getLinkedPhone())
                    .outreachPausedUntil(a.getOutreachPausedUntil())
                    .reachoutLockedUntil(a.getReachoutLockedUntil())
                    .build();
        }
    }
}
