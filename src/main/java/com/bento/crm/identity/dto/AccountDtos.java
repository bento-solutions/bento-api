package com.bento.crm.identity.dto;

import com.bento.crm.auth.dto.OrganizationChoiceDto;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Requests and answers about a person's Account: its address, its password and its Organizations. */
public final class AccountDtos {

    private AccountDtos() {
    }

    /** New passwords follow sign-up's rule. */
    private static final int MIN_PASSWORD = 12;
    private static final int MAX_PASSWORD = 128;

    public record EmailRequest(
            @NotBlank(message = "Email is required")
            @Email(message = "Email should be valid")
            @Size(max = 254)
            String email) {
    }

    public record TokenRequest(
            @NotBlank(message = "The link is missing its token")
            @Size(max = 200)
            String token) {
    }

    public record ResetPasswordRequest(
            @NotBlank(message = "The link is missing its token")
            @Size(max = 200)
            String token,
            @NotBlank(message = "Choose a password")
            @Size(min = MIN_PASSWORD, max = MAX_PASSWORD, message = "Password must be between 12 and 128 characters")
            String password) {
    }

    public record ChangePasswordRequest(
            @JsonProperty("current_password")
            @NotBlank(message = "Enter your current password")
            @Size(max = MAX_PASSWORD)
            String currentPassword,
            @JsonProperty("new_password")
            @NotBlank(message = "Choose a new password")
            @Size(min = MIN_PASSWORD, max = MAX_PASSWORD, message = "Password must be between 12 and 128 characters")
            String newPassword) {
    }

    /**
     * {@code refresh_token} is the session being left. It is optional: the browser keeps it in
     * storage and sends it so that session ends instead of lingering until it expires.
     */
    public record SwitchOrganizationRequest(
            @JsonProperty("organization_id")
            @NotNull(message = "Choose an organization")
            UUID organizationId,
            @JsonProperty("refresh_token")
            @Size(max = 2000)
            String refreshToken) {
    }

    public record CreateOrganizationRequest(
            @NotBlank(message = "Organization name is required")
            @Size(max = 200, message = "Organization name must be at most 200 characters")
            String name,
            @Size(max = 100)
            String industry,
            @JsonProperty("default_currency")
            @Size(min = 3, max = 3, message = "Currency must be a 3-letter ISO code")
            String defaultCurrency,
            @Size(max = 64)
            String timezone) {
    }

    /** The Organizations a signed-in person can switch to, the current one included. */
    public record OrganizationsResponse(
            @JsonProperty("current_organization_id") UUID currentOrganizationId,
            List<OrganizationChoiceDto> organizations) {
    }

    /** Answer to a request that only completes from an emailed link. */
    public record CheckEmailResponse(String status, String email) {
        public static CheckEmailResponse sentTo(String email) {
            return new CheckEmailResponse("check_email", email);
        }
    }
}
