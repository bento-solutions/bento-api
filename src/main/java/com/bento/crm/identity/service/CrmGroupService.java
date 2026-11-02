package com.bento.crm.identity.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.identity.dto.CreateGroupMeetingRequest;
import com.bento.crm.identity.dto.CreateGroupMessageRequest;
import com.bento.crm.identity.dto.CreateGroupRequest;
import com.bento.crm.identity.model.CrmGroup;
import com.bento.crm.identity.model.GroupMeeting;
import com.bento.crm.identity.model.GroupMessage;
import com.bento.crm.identity.repository.AppUserRepository;
import com.bento.crm.identity.repository.CrmGroupRepository;
import com.bento.crm.identity.repository.GroupMeetingRepository;
import com.bento.crm.identity.repository.GroupMessageRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class CrmGroupService {

    private final CrmGroupRepository crmGroupRepository;
    private final GroupMessageRepository groupMessageRepository;
    private final GroupMeetingRepository groupMeetingRepository;
    private final AppUserRepository appUserRepository;

    public CrmGroupService(CrmGroupRepository crmGroupRepository,
                            GroupMessageRepository groupMessageRepository,
                            GroupMeetingRepository groupMeetingRepository,
                            AppUserRepository appUserRepository) {
        this.crmGroupRepository = crmGroupRepository;
        this.groupMessageRepository = groupMessageRepository;
        this.groupMeetingRepository = groupMeetingRepository;
        this.appUserRepository = appUserRepository;
    }

    private static UUID getCurrentUserId() {
        return UUID.fromString((String) SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }

    /** group_member has no organization column, so this is what keeps a group inside its tenant. */
    private void requireUsersInOrganization(UUID orgId, Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        Set<UUID> distinct = new HashSet<>(userIds);
        if (appUserRepository.countByOrganizationIdAndIdIn(orgId, distinct) != distinct.size()) {
            throw new IllegalArgumentException("Every person must belong to this organization");
        }
    }

    @Transactional
    public CrmGroup createTeam(CreateGroupRequest request) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        requireUsersInOrganization(orgId, request.getMemberUserIds());

        // Whoever creates a group is in it, whatever the request lists.
        Set<UUID> members = new LinkedHashSet<>();
        members.add(getCurrentUserId());
        if (request.getMemberUserIds() != null) {
            members.addAll(request.getMemberUserIds());
        }

        CrmGroup group = CrmGroup.builder()
                .name(request.getName())
                .description(request.getDescription())
                .memberUserIds(members)
                .build();

        group.setOrganizationId(orgId);
        return crmGroupRepository.save(group);
    }

    public CrmGroup getTeam(UUID teamId) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return crmGroupRepository.findByOrganizationIdAndId(orgId, teamId)
                .orElseThrow(() -> new ResourceNotFoundException("Team not found"));
    }

    public Page<CrmGroup> listTeams(Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return crmGroupRepository.findByOrganizationId(orgId, pageable);
    }

    @Transactional
    public CrmGroup updateTeam(UUID teamId, CreateGroupRequest request) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        requireUsersInOrganization(orgId, request.getMemberUserIds());
        CrmGroup group = crmGroupRepository.findByOrganizationIdAndId(orgId, teamId)
                .orElseThrow(() -> new ResourceNotFoundException("Team not found"));

        group.setName(request.getName());
        group.setDescription(request.getDescription());
        if (request.getMemberUserIds() != null) {
            group.getMemberUserIds().clear();
            group.getMemberUserIds().addAll(request.getMemberUserIds());
        }

        return crmGroupRepository.save(group);
    }

    @Transactional
    public void deleteTeam(UUID teamId) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        CrmGroup group = crmGroupRepository.findByOrganizationIdAndId(orgId, teamId)
                .orElseThrow(() -> new ResourceNotFoundException("Team not found"));

        // Neither table cascades from crm_group, so a group with any history could not be deleted.
        groupMessageRepository.deleteByGroupId(orgId, teamId);
        groupMeetingRepository.deleteByGroupId(orgId, teamId);
        crmGroupRepository.delete(group);
    }

    public Page<GroupMessage> listMessages(UUID groupId, Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        getTeam(groupId);
        return groupMessageRepository.findByGroupId(orgId, groupId, pageable);
    }

    @Transactional
    public GroupMessage createMessage(UUID groupId, CreateGroupMessageRequest request) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        getTeam(groupId);

        UUID authorId = getCurrentUserId();
        GroupMessage toSave = GroupMessage.builder()
                .groupId(groupId)
                .authorUserId(authorId)
                .content(request.getContent())
                .readByUserIds(List.of(authorId))
                .build();
        toSave.setOrganizationId(orgId);
        return groupMessageRepository.save(toSave);
    }

    public Page<GroupMeeting> listMeetings(UUID groupId, Pageable pageable) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        getTeam(groupId);
        return groupMeetingRepository.findByGroupId(orgId, groupId, pageable);
    }

    @Transactional
    public GroupMeeting createMeeting(UUID groupId, CreateGroupMeetingRequest request) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        getTeam(groupId);
        requireUsersInOrganization(orgId, request.getAttendeeUserIds());

        GroupMeeting toSave = GroupMeeting.builder()
                .groupId(groupId)
                .title(request.getTitle())
                .description(request.getDescription())
                .scheduledAt(request.getScheduledAt())
                .durationMinutes(request.getDurationMinutes())
                .createdByUserId(getCurrentUserId())
                .attendeeUserIds(request.getAttendeeUserIds())
                .meetingLink(request.getMeetingLink())
                .build();
        toSave.setOrganizationId(orgId);
        return groupMeetingRepository.save(toSave);
    }
}
