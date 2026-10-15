package com.bento.crm.organization.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateOrganizationRequest {

    @NotBlank(message = "Organization name is required")
    @Size(max = 200, message = "Organization name must be at most 200 characters")
    private String name;

    @Size(max = 100, message = "Industry must be at most 100 characters")
    private String industry;

    @JsonProperty("default_currency")
    @Size(min = 3, max = 3, message = "Currency must be a 3-letter ISO code")
    private String defaultCurrency;

    @Size(max = 64, message = "Timezone must be at most 64 characters")
    private String timezone;

    /** When stock leaves the warehouse; null takes the industry's default. */
    @JsonProperty("stock_issue_trigger")
    private com.bento.crm.stock.model.StockSettings.IssueTrigger stockIssueTrigger;

    @JsonProperty("admin_email")
    @NotBlank(message = "Admin email is required")
    @Email(message = "Admin email must be a valid email address")
    @Size(max = 254, message = "Admin email must be at most 254 characters")
    private String adminEmail;

    @JsonProperty("admin_name")
    @NotBlank(message = "Admin name is required")
    @Size(max = 150, message = "Admin name must be at most 150 characters")
    private String adminName;

    // Signup mints the tenant's first ADMIN, so this password guards everything in the new
    // organization. Require a 12-char minimum with at least one letter and one digit; the upper
    // bound keeps a pathological input from tying up BCrypt.
    @JsonProperty("admin_password")
    @NotBlank(message = "Admin password is required")
    @Size(min = 12, max = 128, message = "Admin password must be between 12 and 128 characters")
    @Pattern(
            regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
            message = "Admin password must contain at least one letter and one digit"
    )
    private String adminPassword;
}
