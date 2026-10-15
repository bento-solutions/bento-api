package com.bento.crm.drive.controller;

import com.bento.crm.drive.dto.DriveDtos.FileResponse;
import com.bento.crm.drive.dto.DriveDtos.FileUpdateRequest;
import com.bento.crm.drive.dto.DriveDtos.FolderRequest;
import com.bento.crm.drive.dto.DriveDtos.FolderResponse;
import com.bento.crm.drive.dto.DriveDtos.Listing;
import com.bento.crm.drive.dto.DriveDtos.ShareRequest;
import com.bento.crm.drive.dto.DriveDtos.ShareResponse;
import com.bento.crm.drive.service.DriveService;
import com.bento.crm.file.model.StoredFile;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/drive")
@RequiredArgsConstructor
@Tag(name = "Drive", description = "Document drive: folders, tags, search and share links")
public class DriveController {

    private final DriveService drive;

    @GetMapping
    @PreAuthorize("hasAuthority('FILES_READ')")
    public Listing list(@RequestParam(required = false) UUID folderId, @RequestParam(required = false) String q,
                        @RequestParam(required = false) String tag, @RequestParam(required = false) UUID partnerId,
                        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "100") int size) {
        return drive.list(folderId, q, tag, partnerId, page, size);
    }

    @GetMapping("/folders")
    @PreAuthorize("hasAuthority('FILES_READ')")
    public List<FolderResponse> folders() {
        return drive.allFolders();
    }

    @GetMapping("/tags")
    @PreAuthorize("hasAuthority('FILES_READ')")
    public List<String> tags() {
        return drive.tags();
    }

    @PostMapping("/folders")
    @PreAuthorize("hasAuthority('FILES_WRITE')")
    @ResponseStatus(HttpStatus.CREATED)
    public FolderResponse createFolder(@Valid @RequestBody FolderRequest r) {
        return drive.createFolder(r);
    }

    @PutMapping("/folders/{id}")
    @PreAuthorize("hasAuthority('FILES_WRITE')")
    public FolderResponse updateFolder(@PathVariable UUID id, @Valid @RequestBody FolderRequest r) {
        return drive.updateFolder(id, r);
    }

    @DeleteMapping("/folders/{id}")
    @PreAuthorize("hasAuthority('FILES_WRITE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFolder(@PathVariable UUID id) {
        drive.deleteFolder(id);
    }

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('FILES_WRITE')")
    @ResponseStatus(HttpStatus.CREATED)
    public FileResponse upload(@RequestParam("file") MultipartFile file, @RequestParam(required = false) UUID folderId,
                               @RequestParam(required = false) List<String> tags, @RequestParam(required = false) UUID partnerId,
                               @RequestParam(required = false) String description) {
        return drive.upload(file, folderId, tags, partnerId, description);
    }

    @PutMapping("/files/{id}")
    @PreAuthorize("hasAuthority('FILES_WRITE')")
    public FileResponse updateFile(@PathVariable UUID id, @Valid @RequestBody FileUpdateRequest r) {
        return drive.updateFile(id, r);
    }

    @DeleteMapping("/files/{id}")
    @PreAuthorize("hasAuthority('FILES_WRITE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFile(@PathVariable UUID id) {
        drive.deleteFile(id);
    }

    /** Inline content, for previews (PDF and images) in the drive. */
    @GetMapping("/files/{id}/content")
    @PreAuthorize("hasAuthority('FILES_READ')")
    public ResponseEntity<Resource> content(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean download) {
        StoredFile f = drive.fileForDownload(id);
        return FileResponses.of(f, drive.open(f), download);
    }

    @GetMapping("/files/{id}/shares")
    @PreAuthorize("hasAuthority('FILES_READ')")
    public List<ShareResponse> shares(@PathVariable UUID id) {
        return drive.shares(id);
    }

    @PostMapping("/files/{id}/shares")
    @PreAuthorize("hasAuthority('FILES_WRITE')")
    @ResponseStatus(HttpStatus.CREATED)
    public ShareResponse share(@PathVariable UUID id, @Valid @RequestBody(required = false) ShareRequest r) {
        return drive.share(id, r == null ? null : r.expiresInDays());
    }

    @DeleteMapping("/shares/{linkId}")
    @PreAuthorize("hasAuthority('FILES_WRITE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID linkId) {
        drive.revokeShare(linkId);
    }
}
