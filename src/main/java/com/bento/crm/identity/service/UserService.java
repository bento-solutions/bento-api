package com.bento.crm.identity.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.model.UserRole;
import com.bento.crm.identity.dto.CreateUserRequest;
import com.bento.crm.identity.dto.UpdateOwnProfileRequest;
import com.bento.crm.identity.dto.UpdateUserRequest;
import com.bento.crm.identity.dto.UserDirectoryEntryDto;
import com.bento.crm.identity.dto.UserResponseDto;
import com.bento.crm.identity.mapper.UserMapper;
import com.bento.crm.identity.model.AppUser;
import com.bento.crm.identity.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;

    @Transactional
    public UserResponseDto createUser(CreateUserRequest request) {
        UUID orgId = TenantContext.getCurrentOrganizationId();

        AppUser user = AppUser.builder()
                .email(request.getEmail())
                .displayName(request.getDisplayName())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(UserRole.valueOf(request.getRole() != null ? request.getRole() : "SALESPERSON"))
                .teamId(request.getTeamId() != null && !request.getTeamId().isBlank() ? UUID.fromString(request.getTeamId()) : null)
                .isActive(true)
                .phone(request.getPhone())
                .jobTitle(request.getJobTitle())
                .language(request.getLanguage() != null ? request.getLanguage() : "en")
                .build();

        user.setOrganizationId(orgId);
        user = userRepository.save(user);
        return userMapper.toResponseDto(user);
    }

    public UserResponseDto getUserById(UUID userId) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        AppUser user = userRepository.findByOrganizationIdAndId(orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return userMapper.toResponseDto(user);
    }

    @Transactional
    public UserResponseDto updateUser(UUID userId, UpdateUserRequest request) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        AppUser user = userRepository.findByOrganizationIdAndId(orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        user.setDisplayName(request.getDisplayName());
        user.setPhone(request.getPhone());
        user.setJobTitle(request.getJobTitle());
        if (request.getLanguage() != null) {
            user.setLanguage(request.getLanguage());
        }
        if (request.getRole() != null) {
            user.setRole(UserRole.valueOf(request.getRole()));
        }
        user.setTeamId(request.getTeamId() != null && !request.getTeamId().isBlank() ? UUID.fromString(request.getTeamId()) : null);

        user = userRepository.save(user);
        return userMapper.toResponseDto(user);
    }

    @Transactional
    public UserResponseDto updateOwnProfile(UUID userId, UpdateOwnProfileRequest request) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        AppUser user = userRepository.findByOrganizationIdAndId(orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (request.getDisplayName() != null) {
            user.setDisplayName(request.getDisplayName());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
        }
        if (request.getJobTitle() != null) {
            user.setJobTitle(request.getJobTitle());
        }
        if (request.getLanguage() != null) {
            user.setLanguage(request.getLanguage());
        }

        user = userRepository.save(user);
        return userMapper.toResponseDto(user);
    }

    public Page<UserResponseDto> listUsers(Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return userRepository.findByOrganizationId(orgId, pageable)
                .map(userMapper::toResponseDto);
    }

    /** Every member of the organization (deactivated ones too, so a former owner still resolves to a name). */
    public List<UserDirectoryEntryDto> listDirectory() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return userRepository.findByOrganizationId(orgId).stream()
                .map(u -> UserDirectoryEntryDto.builder()
                        .id(u.getId())
                        .displayName(u.getDisplayName())
                        .initials(userMapper.deriveInitials(u))
                        .avatarColor(u.getAvatarColor())
                        .teamId(u.getTeamId())
                        .isActive(u.getIsActive())
                        .build())
                .toList();
    }

    @Transactional
    public void deactivateUser(UUID userId) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        AppUser user = userRepository.findByOrganizationIdAndId(orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        long activeAdmins = userRepository.countActiveAdminsByOrganizationId(user.getOrganizationId());
        if (user.getRole() == UserRole.ADMIN && activeAdmins <= 1) {
            throw new IllegalStateException("Cannot deactivate the last admin");
        }

        user.setIsActive(false);
        userRepository.save(user);
    }
}
