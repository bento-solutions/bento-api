package com.bento.crm.identity.controller;

import com.bento.crm.common.dto.PageResponse;
import com.bento.crm.identity.dto.CreateUserRequest;
import com.bento.crm.identity.dto.UpdateOwnProfileRequest;
import com.bento.crm.identity.dto.UpdateUserRequest;
import com.bento.crm.identity.dto.UserDirectoryEntryDto;
import com.bento.crm.identity.dto.UserResponseDto;
import com.bento.crm.identity.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "User management endpoints")
public class UserController {

    private final UserService userService;

    @PostMapping
    @PreAuthorize("hasAuthority('USERS_WRITE')")
    @Operation(summary = "Create user", description = "Create new user in the organization")
    public ResponseEntity<UserResponseDto> createUser(@Valid @RequestBody CreateUserRequest request) {
        UserResponseDto user = userService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(user);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('USERS_READ')")
    @Operation(summary = "Get user by ID", description = "Retrieve user details")
    public ResponseEntity<UserResponseDto> getUser(@PathVariable UUID id) {
        UserResponseDto user = userService.getUserById(id);
        return ResponseEntity.ok(user);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('USERS_READ')")
    @Operation(summary = "List users", description = "List all users in the organization")
    public ResponseEntity<PageResponse<UserResponseDto>> listUsers(Pageable pageable) {
        Page<UserResponseDto> page = userService.listUsers(pageable);
        return ResponseEntity.ok(PageResponse.fromPage(page));
    }

    @GetMapping("/directory")
    @Operation(summary = "User directory", description = "Names and avatars of every member of the organization, readable by any signed-in user (no USERS_READ needed) so lead owners and assignees can be shown and filtered on")
    public ResponseEntity<List<UserDirectoryEntryDto>> directory() {
        return ResponseEntity.ok(userService.listDirectory());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('USERS_WRITE')")
    @Operation(summary = "Update user", description = "Update user information")
    public ResponseEntity<UserResponseDto> updateUser(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        UserResponseDto user = userService.updateUser(id, request);
        return ResponseEntity.ok(user);
    }

    @PatchMapping("/me")
    @Operation(summary = "Update own profile", description = "Update the authenticated user's own display name, phone, job title, and language -- does not require USERS_WRITE and cannot change role or team")
    public ResponseEntity<UserResponseDto> updateOwnProfile(Authentication authentication, @Valid @RequestBody UpdateOwnProfileRequest request) {
        UUID userId = UUID.fromString(authentication.getName());
        UserResponseDto user = userService.updateOwnProfile(userId, request);
        return ResponseEntity.ok(user);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('USERS_WRITE')")
    @Operation(summary = "Deactivate user", description = "Deactivate user account")
    public ResponseEntity<Void> deactivateUser(@PathVariable UUID id) {
        userService.deactivateUser(id);
        return ResponseEntity.noContent().build();
    }
}
