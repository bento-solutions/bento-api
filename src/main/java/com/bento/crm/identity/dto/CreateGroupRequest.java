package com.bento.crm.identity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateGroupRequest {

    @NotBlank
    private String name;

    private String description;

    /** On update, null leaves the members as they are; a list replaces them. */
    private List<UUID> memberUserIds;
}
