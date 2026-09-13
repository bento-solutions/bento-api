package com.bento.crm.invitation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * What the unauthenticated acceptance page is allowed to learn from a token. Kept to the
 * minimum needed to render a trustworthy page -- no ids, no member list, nothing that would
 * turn a leaked token into a source of organization intelligence.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvitationPreviewResponse {

    private java.util.UUID id;

    private String email;

    @JsonProperty("organization_name")
    private String organizationName;

    @JsonProperty("role")
    private String role;

    @JsonProperty("display_name")
    private String displayName;

    @JsonProperty("job_title")
    private String jobTitle;

    @JsonProperty("invited_by_name")
    private String invitedByName;

    @JsonProperty("team_id")
    private java.util.UUID teamId;

    @JsonProperty("team_name")
    private String teamName;

    @JsonProperty("expires_at")
    private Instant expiresAt;
}
