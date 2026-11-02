package com.bento.crm.identity.model;

import com.bento.crm.common.model.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "group_meeting")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupMeeting extends BaseTenantEntity {

    @Column(nullable = false, columnDefinition = "uuid")
    private UUID groupId;

    @Column(nullable = false)
    private String title;

    private String description;

    @Column(nullable = false)
    private Instant scheduledAt;

    private Integer durationMinutes;

    @Column(nullable = false, columnDefinition = "uuid")
    private UUID createdByUserId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "uuid[]")
    private List<UUID> attendeeUserIds;

    private String meetingLink;
}
