package com.bento.crm.identity.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * The non-sensitive slice of a user that every signed-in member may see, so an owner / assignee
 * can be named (and filtered on) by roles that lack USERS_READ. No email, phone or role.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDirectoryEntryDto {
    private UUID id;

    @JsonProperty("display_name")
    private String displayName;

    private String initials;

    @JsonProperty("avatar_color")
    private String avatarColor;

    @JsonProperty("team_id")
    private UUID teamId;

    @JsonProperty("is_active")
    private Boolean isActive;
}
