package com.bento.crm.identity.controller;

import com.bento.crm.auth.dto.LoginResponse;
import com.bento.crm.auth.service.AuthService;
import com.bento.crm.common.config.JwtProperties;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.identity.dto.AccountDtos;
import com.bento.crm.identity.model.AppUser;
import com.bento.crm.identity.repository.AppUserRepository;
import com.bento.crm.identity.service.AccountService;
import com.bento.crm.identity.service.UserService;
import com.bento.crm.organization.service.OrganizationService;
import com.bento.crm.portal.PortalPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * What a signed-in person does with their Account rather than inside one Organization: see and
 * switch between their Organizations, create another, leave the current one, change their
 * password and end their sessions. Staff only; a client-portal session is refused.
 */
@RestController
@RequestMapping("/account")
@RequiredArgsConstructor
@Tag(name = "Account", description = "The signed-in person's account and organizations")
public class AccountController {

    private final AuthService authService;
    private final AccountService accountService;
    private final UserService userService;
    private final OrganizationService organizationService;
    private final AppUserRepository userRepository;
    private final JwtProperties jwtProperties;

    @GetMapping("/organizations")
    @Operation(summary = "List my organizations", description = "Every organization the account can open, most recently used first")
    public AccountDtos.OrganizationsResponse organizations(Authentication authentication) {
        AppUser current = currentMembership(authentication);
        return new AccountDtos.OrganizationsResponse(current.getOrganizationId(),
                authService.organizationChoices(authService.activeMemberships(current.getAccountId())));
    }

    @PostMapping("/switch")
    @Operation(summary = "Switch organization", description = "Opens a session in another of my organizations")
    public ResponseEntity<LoginResponse> switchOrganization(Authentication authentication,
                                                            @Valid @RequestBody AccountDtos.SwitchOrganizationRequest request) {
        LoginResponse session = authService.switchOrganization(currentMembership(authentication),
                request.organizationId(), request.refreshToken());
        return withSessionCookie(ResponseEntity.ok(), session);
    }

    @PostMapping("/organizations")
    @Operation(summary = "Create another organization", description = "I become its admin and get a session in it")
    public ResponseEntity<LoginResponse> createOrganization(Authentication authentication,
                                                            @Valid @RequestBody AccountDtos.CreateOrganizationRequest request) {
        AppUser admin = organizationService.createAnotherOrganization(currentMembership(authentication), request);
        return withSessionCookie(ResponseEntity.status(HttpStatus.CREATED), authService.issueSession(admin));
    }

    @PostMapping("/leave")
    @Operation(summary = "Leave the current organization", description = "Refused for its last active admin")
    public ResponseEntity<Void> leave(Authentication authentication) {
        userService.leave(currentMembership(authentication));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearedSessionCookie().toString()).build();
    }

    @PutMapping("/password")
    @Operation(summary = "Change my password", description = "Ends my other sessions; answers with a new session for this device")
    public ResponseEntity<LoginResponse> changePassword(Authentication authentication,
                                                        @Valid @RequestBody AccountDtos.ChangePasswordRequest request) {
        AppUser current = currentMembership(authentication);
        accountService.changePassword(current.getAccountId(), request.currentPassword(), request.newPassword());
        return withSessionCookie(ResponseEntity.ok(), authService.issueSession(current));
    }

    @PostMapping("/sign-out-everywhere")
    @Operation(summary = "Sign out everywhere", description = "Ends every session of my account, in every organization")
    public ResponseEntity<Void> signOutEverywhere(Authentication authentication) {
        accountService.signOutEverywhere(currentMembership(authentication).getAccountId());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearedSessionCookie().toString()).build();
    }

    private AppUser currentMembership(Authentication authentication) {
        if (authentication == null || authentication.getDetails() instanceof PortalPrincipal) {
            throw new AccessDeniedException("Staff sign-in required");
        }
        return userRepository.findById(UUID.fromString(authentication.getName()))
                .filter(m -> Boolean.TRUE.equals(m.getIsActive()))
                .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));
    }

    private ResponseEntity<LoginResponse> withSessionCookie(ResponseEntity.BodyBuilder builder, LoginResponse session) {
        return builder.header(HttpHeaders.SET_COOKIE, sessionCookie(session.getRefreshToken(),
                jwtProperties.getRefreshTokenExpiry() / 1000).toString()).body(session);
    }

    private ResponseCookie clearedSessionCookie() {
        return sessionCookie("", 0);
    }

    /** Same cookie AuthController sets at sign-in, so the browser keeps one refresh token. */
    private static ResponseCookie sessionCookie(String token, long maxAgeSeconds) {
        return ResponseCookie.from("refreshToken", token != null ? token : "")
                .httpOnly(true)
                .secure(false)
                .path("/api/v1/auth")
                .maxAge(maxAgeSeconds)
                .sameSite("Lax")
                .build();
    }
}
