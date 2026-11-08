package com.bento.crm.invitation.controller;

import com.bento.crm.auth.dto.LoginResponse;
import com.bento.crm.invitation.dto.AcceptInvitationRequest;
import com.bento.crm.invitation.dto.InvitationPreviewResponse;
import com.bento.crm.identity.dto.AccountDtos;
import com.bento.crm.invitation.service.InvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Unauthenticated by design: the invitee has no account yet. The token is the only credential,
 * and it is the sole source of the organization, role and email that the created account gets --
 * see {@link InvitationService#accept}. Whitelisted in SecurityConfig and
 * TenantFilterInterceptor, both of which must stay in step with the paths below.
 */
@RestController
@RequestMapping("/public/invitations")
@RequiredArgsConstructor
@Tag(name = "Invitations (public)", description = "Invitation acceptance for users without an account")
public class PublicInvitationController {

    private final InvitationService invitationService;

    @GetMapping
    @Operation(summary = "Preview an invitation", description = "Resolve a token into the organization, email and pre-assigned role shown on the acceptance page")
    public ResponseEntity<InvitationPreviewResponse> preview(@RequestParam("token") String token) {
        return ResponseEntity.ok(invitationService.preview(token));
    }

    /**
     * 200 with a session, or 202 "check_email" when the address must be confirmed first: the copied
     * link doesn't prove the invitee reads the invited inbox, the emailed one does.
     */
    @PostMapping("/accept")
    @Operation(summary = "Accept an invitation", description = "Create the account from the invitation and return a signed-in session")
    public ResponseEntity<?> accept(@Valid @RequestBody AcceptInvitationRequest request) {
        InvitationService.AcceptOutcome outcome = invitationService.accept(request);
        if (outcome.session() == null) {
            return ResponseEntity.accepted().body(AccountDtos.CheckEmailResponse.sentTo(outcome.checkEmail()));
        }
        return ResponseEntity.ok(outcome.session());
    }
}
