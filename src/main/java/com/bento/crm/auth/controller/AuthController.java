package com.bento.crm.auth.controller;

import com.bento.crm.auth.dto.LoginRequest;
import com.bento.crm.auth.dto.LoginResponse;
import com.bento.crm.auth.dto.RefreshTokenRequest;
import com.bento.crm.auth.service.AuthService;
import com.bento.crm.identity.dto.AccountDtos;
import com.bento.crm.identity.dto.UserResponseDto;
import com.bento.crm.identity.service.AccountService;
import com.bento.crm.identity.service.UserService;
import com.bento.crm.common.config.JwtProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Authentication endpoints")
public class AuthController {

    private final AuthService authService;
    private final AccountService accountService;
    private final UserService userService;
    private final JwtProperties jwtProperties;

    @PostMapping("/login")
    @Operation(summary = "Login with email and password", description = "Authenticate and receive JWT tokens")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request.getEmail(), request.getPassword(), request.getOrganizationId());
        ResponseCookie cookie = createRefreshTokenCookie(response.getRefreshToken());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(response);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh access token", description = "Use refresh token to get new access token")
    public ResponseEntity<LoginResponse> refresh(
            @RequestBody(required = false) RefreshTokenRequest request,
            @CookieValue(name = "refreshToken", required = false) String cookieRefreshToken) {
        String token = (request != null && request.getRefreshToken() != null && !request.getRefreshToken().isBlank())
                ? request.getRefreshToken()
                : cookieRefreshToken;
        if (token == null || token.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        LoginResponse response = authService.refresh(token);
        ResponseCookie cookie = createRefreshTokenCookie(response.getRefreshToken());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(response);
    }

    /**
     * Ends this device's session: the refresh token in the body (or cookie) is revoked, and the
     * person's other devices stay signed in. Without one, every session of the Membership ends,
     * as before. "Sign out everywhere" (/account/sign-out-everywhere) covers every Organization.
     */
    @PostMapping("/logout")
    @Operation(summary = "Logout", description = "Ends this device's session")
    public ResponseEntity<Void> logout(Authentication authentication,
                                       @RequestBody(required = false) RefreshTokenRequest request,
                                       @CookieValue(name = "refreshToken", required = false) String cookieRefreshToken) {
        if (authentication != null) {
            UUID userId = UUID.fromString(authentication.getName());
            String token = (request != null && request.getRefreshToken() != null && !request.getRefreshToken().isBlank())
                    ? request.getRefreshToken()
                    : cookieRefreshToken;
            authService.logout(userId, token);
        }
        ResponseCookie cookie = deleteRefreshTokenCookie();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .build();
    }

    @PostMapping("/verify-email")
    @Operation(summary = "Confirm an email address", description = "Uses the token from the emailed confirmation link")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody AccountDtos.TokenRequest request) {
        accountService.verifyEmail(request.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/verification")
    @Operation(summary = "Send the confirmation link again",
            description = "Always 202, so the answer doesn't tell whether the address has an account")
    public ResponseEntity<Void> resendVerification(@Valid @RequestBody AccountDtos.EmailRequest request) {
        accountService.resendVerification(request.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password-reset")
    @Operation(summary = "Email a link to choose a new password",
            description = "Always 202, so the answer doesn't tell whether the address has an account")
    public ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody AccountDtos.EmailRequest request) {
        accountService.requestPasswordReset(request.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password-reset/confirm")
    @Operation(summary = "Choose a new password from the emailed link",
            description = "Signs the account out everywhere; the person then signs in with the new password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody AccountDtos.ResetPasswordRequest request) {
        accountService.resetPassword(request.token(), request.password());
        return ResponseEntity.noContent().build();
    }

    private ResponseCookie createRefreshTokenCookie(String token) {
        long maxAgeSeconds = jwtProperties.getRefreshTokenExpiry() / 1000;
        return ResponseCookie.from("refreshToken", token != null ? token : "")
                .httpOnly(true)
                .secure(false) // allows HTTP in local dev while HTTPS in production works
                .path("/api/v1/auth")
                .maxAge(maxAgeSeconds)
                .sameSite("Lax")
                .build();
    }

    private ResponseCookie deleteRefreshTokenCookie() {
        return ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(false)
                .path("/api/v1/auth")
                .maxAge(0)
                .sameSite("Lax")
                .build();
    }

    @GetMapping("/me")
    @Operation(summary = "Get current user info", description = "Get authenticated user information")
    public ResponseEntity<UserResponseDto> getCurrentUser(Authentication authentication) {
        if (authentication == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(userService.getUserById(userId));
    }
}
