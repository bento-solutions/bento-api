package com.bento.crm.invitation.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A signed-in user's session does not prove the invited address; the emailed token does. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AcceptLoggedInInvitationRequest {

    @NotBlank
    private String token;
}
