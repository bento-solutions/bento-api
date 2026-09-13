package com.bento.crm.invitation.dto;

import com.bento.crm.invitation.model.UserInvitation;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Admin-facing view of an invitation. Never carries the token. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvitationResponse {

    private UUID id;

    @JsonProperty("organization_id")
    private UUID organizationId;

    @JsonProperty("organization_name")
    private String organizationName;

    private String email;

    private String role;

    @JsonProperty("team_id")
    private UUID teamId;

    @JsonProperty("display_name")
    private String displayName;

    @JsonProperty("job_title")
    private String jobTitle;

    private String language;

    /** PENDING, ACCEPTED, REVOKED, or the derived EXPIRED. */
    private String status;

    @JsonProperty("expires_at")
    private Instant expiresAt;

    @JsonProperty("accepted_at")
    private Instant acceptedAt;

    @JsonProperty("revoked_at")
    private Instant revokedAt;

    @JsonProperty("accepted_user_id")
    private UUID acceptedUserId;

    @JsonProperty("last_sent_at")
    private Instant lastSentAt;

    @JsonProperty("send_count")
    private Integer sendCount;

    @JsonProperty("invited_by")
    private UUID invitedBy;

    @JsonProperty("invited_by_name")
    private String invitedByName;

    @JsonProperty("team_name")
    private String teamName;

    private String token;

    @JsonProperty("invitation_url")
    private String invitationUrl;

    @JsonProperty("created_at")
    private Instant createdAt;

    public static InvitationResponse fromEntity(UserInvitation invitation, String invitedByName) {
        return fromEntity(invitation, invitedByName, null, null, null);
    }

    public static InvitationResponse fromEntity(UserInvitation invitation, String invitedByName, String teamName, String acceptUrlBase) {
        return fromEntity(invitation, invitedByName, teamName, acceptUrlBase, null);
    }

    public static InvitationResponse fromEntity(UserInvitation invitation, String invitedByName, String teamName, String acceptUrlBase, String organizationName) {
        String url = null;
        String raw = invitation.getRawToken();
        if (raw != null && acceptUrlBase != null && !acceptUrlBase.isBlank()) {
            String separator = acceptUrlBase.contains("?") ? "&" : "?";
            url = acceptUrlBase + separator + "token=" + raw;
        }

        return InvitationResponse.builder()
                .id(invitation.getId())
                .organizationId(invitation.getOrganizationId())
                .organizationName(organizationName)
                .email(invitation.getEmail())
                .role(invitation.getRole().name())
                .teamId(invitation.getTeamId())
                .teamName(teamName)
                .displayName(invitation.getDisplayName())
                .jobTitle(invitation.getJobTitle())
                .language(invitation.getLanguage())
                .status(invitation.displayStatus())
                .expiresAt(invitation.getExpiresAt())
                .acceptedAt(invitation.getAcceptedAt())
                .revokedAt(invitation.getRevokedAt())
                .acceptedUserId(invitation.getAcceptedUserId())
                .lastSentAt(invitation.getLastSentAt())
                .sendCount(invitation.getSendCount())
                .invitedBy(invitation.getCreatedBy())
                .invitedByName(invitedByName)
                .createdAt(invitation.getCreatedAt())
                .token(raw)
                .invitationUrl(url)
                .build();
    }
}
