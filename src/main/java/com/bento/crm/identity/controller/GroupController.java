package com.bento.crm.identity.controller;

import com.bento.crm.common.dto.PageResponse;
import com.bento.crm.identity.dto.CreateGroupMeetingRequest;
import com.bento.crm.identity.dto.CreateGroupMessageRequest;
import com.bento.crm.identity.dto.CreateTeamRequest;
import com.bento.crm.identity.model.CrmGroup;
import com.bento.crm.identity.model.GroupMessage;
import com.bento.crm.identity.model.GroupMeeting;
import com.bento.crm.identity.service.CrmGroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.bento.crm.identity.service.GroupStreamService;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/groups")
@Tag(name = "Groups", description = "Group management endpoints")
public class GroupController {

    private final CrmGroupService crmGroupService;
    private final GroupStreamService groupStreamService;

    public GroupController(CrmGroupService crmGroupService, GroupStreamService groupStreamService) {
        this.crmGroupService = crmGroupService;
        this.groupStreamService = groupStreamService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('GROUPS_CREATE')")
    @Operation(summary = "Create group", description = "Create new group in the organization")
    public ResponseEntity<CrmGroup> createGroup(@Valid @RequestBody CreateTeamRequest request) {
        CrmGroup group = crmGroupService.createTeam(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(group);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('GROUPS_READ')")
    @Operation(summary = "Get group by ID", description = "Retrieve group details")
    public ResponseEntity<CrmGroup> getGroup(@PathVariable UUID id) {
        CrmGroup group = crmGroupService.getTeam(id);
        return ResponseEntity.ok(group);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('GROUPS_READ')")
    @Operation(summary = "List groups", description = "List all groups in the organization")
    public ResponseEntity<PageResponse<CrmGroup>> listGroups(Pageable pageable) {
        Page<CrmGroup> page = crmGroupService.listTeams(pageable);
        return ResponseEntity.ok(PageResponse.fromPage(page));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('GROUPS_WRITE')")
    @Operation(summary = "Update group", description = "Update group information")
    public ResponseEntity<CrmGroup> updateGroup(@PathVariable UUID id, @Valid @RequestBody CreateTeamRequest request) {
        CrmGroup group = crmGroupService.updateTeam(id, request);
        return ResponseEntity.ok(group);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('GROUPS_DELETE')")
    @Operation(summary = "Delete group", description = "Delete group record")
    public ResponseEntity<Void> deleteGroup(@PathVariable UUID id) {
        crmGroupService.deleteTeam(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{groupId}/messages")
    @PreAuthorize("hasAuthority('GROUPS_READ')")
    @Operation(summary = "List group messages", description = "List all messages in a group")
    public ResponseEntity<PageResponse<GroupMessage>> getGroupMessages(@PathVariable UUID groupId, Pageable pageable) {
        return ResponseEntity.ok(PageResponse.fromPage(crmGroupService.listMessages(groupId, pageable)));
    }

    @PostMapping("/{groupId}/messages")
    @PreAuthorize("hasAuthority('GROUPS_WRITE')")
    @Operation(summary = "Create group message", description = "Post a message to a group")
    public ResponseEntity<GroupMessage> createGroupMessage(@PathVariable UUID groupId, @Valid @RequestBody CreateGroupMessageRequest request) {
        GroupMessage message = crmGroupService.createMessage(groupId, request);
        groupStreamService.broadcast(groupId, message);
        return ResponseEntity.status(HttpStatus.CREATED).body(message);
    }

    @GetMapping("/{groupId}/stream")
    @PreAuthorize("hasAuthority('GROUPS_READ')")
    @Operation(summary = "Subscribe to group event stream", description = "Server-Sent Events stream for real-time collaboration")
    public SseEmitter subscribeToGroupStream(@PathVariable UUID groupId) {
        return groupStreamService.subscribe(groupId);
    }

    @GetMapping("/{groupId}/meetings")
    @PreAuthorize("hasAuthority('GROUPS_READ')")
    @Operation(summary = "List group meetings", description = "List all meetings in a group")
    public ResponseEntity<PageResponse<GroupMeeting>> getGroupMeetings(@PathVariable UUID groupId, Pageable pageable) {
        return ResponseEntity.ok(PageResponse.fromPage(crmGroupService.listMeetings(groupId, pageable)));
    }

    @PostMapping("/{groupId}/meetings")
    @PreAuthorize("hasAuthority('GROUPS_WRITE')")
    @Operation(summary = "Create group meeting", description = "Schedule a meeting for a group")
    public ResponseEntity<GroupMeeting> createGroupMeeting(@PathVariable UUID groupId, @Valid @RequestBody CreateGroupMeetingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(crmGroupService.createMeeting(groupId, request));
    }
}
