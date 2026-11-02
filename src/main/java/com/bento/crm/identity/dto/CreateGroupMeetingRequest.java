package com.bento.crm.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateGroupMeetingRequest {

    @NotBlank
    private String title;

    private String description;

    @NotNull
    private Instant scheduledAt;

    @Positive
    private Integer durationMinutes;

    private List<UUID> attendeeUserIds;

    private String meetingLink;
}
