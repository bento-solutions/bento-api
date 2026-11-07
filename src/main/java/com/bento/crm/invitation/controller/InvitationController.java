package com.bento.crm.invitation.controller;

import com.bento.crm.invitation.dto.AcceptLoggedInInvitationRequest;
import com.bento.crm.invitation.dto.CreateInvitationRequest;
import com.bento.crm.invitation.dto.InvitationResponse;
import com.bento.crm.invitation.service.InvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Admin surface, gated on USERS_WRITE: an invitation is the only way a person joins an
 * organization.
 */
@RestController
@RequestMapping("/invitations")
@RequiredArgsConstructor
@Tag(name = "Invitations", description = "Invite users to the organization by email")
public class InvitationController {

    private final InvitationService invitationService;

    @PostMapping
    @PreAuthorize("hasAuthority('USERS_WRITE')")
    @Operation(summary = "Invite a user", description = "Email an invitation with a pre-assigned role and team")
    public ResponseEntity<InvitationResponse> invite(@Valid @RequestBody CreateInvitationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(invitationService.invite(request));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('USERS_READ')")
    @Operation(summary = "List invitations", description = "All invitations for the organization, newest first")
    public ResponseEntity<List<InvitationResponse>> list() {
        return ResponseEntity.ok(invitationService.list());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('USERS_WRITE')")
    @Operation(summary = "Edit a pending invitation", description = "Change the pre-assigned role, team, name or job title")
    public ResponseEntity<InvitationResponse> update(@PathVariable UUID id,
                                                     @Valid @RequestBody CreateInvitationRequest request) {
        return ResponseEntity.ok(invitationService.update(id, request));
    }

    @PostMapping("/{id}/resend")
    @PreAuthorize("hasAuthority('USERS_WRITE')")
    @Operation(summary = "Resend an invitation", description = "Issues a new token, invalidating the previous link, and re-sends the email")
    public ResponseEntity<InvitationResponse> resend(@PathVariable UUID id) {
        return ResponseEntity.ok(invitationService.resend(id));
    }

    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasAuthority('USERS_WRITE')")
    @Operation(summary = "Revoke an invitation", description = "Invalidates the outstanding link immediately")
    public ResponseEntity<InvitationResponse> revoke(@PathVariable UUID id) {
        return ResponseEntity.ok(invitationService.revoke(id));
    }

    @PostMapping("/{id}/accept-logged-in")
    @Operation(summary = "Accept invitation as logged-in user", description = "Accept an invitation with the emailed token and switch to the target organization without re-entering the password")
    public ResponseEntity<com.bento.crm.auth.dto.LoginResponse> acceptLoggedIn(@PathVariable UUID id,
                                                                              @Valid @RequestBody AcceptLoggedInInvitationRequest request) {
        UUID currentUserId = UUID.fromString((String) org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        return ResponseEntity.ok(invitationService.acceptForLoggedInUser(id, request.getToken(), currentUserId));
    }
}
