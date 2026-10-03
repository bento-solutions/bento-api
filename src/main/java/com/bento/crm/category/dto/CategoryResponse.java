package com.bento.crm.category.dto;

import com.bento.crm.category.model.Category;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CategoryResponse {

    private UUID id;
    private String name;
    private String color;
    /** Tickets and tasks currently carrying this category — shown before a delete. */
    private long ticketCount;
    private long taskCount;
    private Instant createdAt;
    private Instant updatedAt;

    public static CategoryResponse fromEntity(Category category, long ticketCount, long taskCount) {
        return CategoryResponse.builder()
                .id(category.getId())
                .name(category.getName())
                .color(category.getColor())
                .ticketCount(ticketCount)
                .taskCount(taskCount)
                .createdAt(category.getCreatedAt())
                .updatedAt(category.getUpdatedAt())
                .build();
    }
}
