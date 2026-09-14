package com.bento.crm.common.dto;

import com.bento.crm.auth.dto.OrganizationChoiceDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiError {
    private String type;
    private String title;
    private int status;
    private String detail;
    private String instance;
    private Instant timestamp;

    @JsonProperty("validation_errors")
    private List<FieldError> validationErrors;

    @JsonProperty("organizations")
    private List<OrganizationChoiceDto> organizations;

    @Data
    @Builder
    @AllArgsConstructor
    public static class FieldError {
        private String field;
        private String message;
    }
}
