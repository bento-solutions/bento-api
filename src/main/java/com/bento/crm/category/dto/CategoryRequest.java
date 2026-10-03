package com.bento.crm.category.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CategoryRequest {

    @NotBlank
    @Size(max = 24, message = "must be at most 24 characters")
    @Pattern(regexp = "^\\S*$", message = "must be a single word (no spaces)")
    private String name;

    @NotBlank
    @Pattern(regexp = "^(slate|blue|sky|violet|emerald|amber|rose)$",
            message = "must be one of slate, blue, sky, violet, emerald, amber, rose")
    private String color;
}
