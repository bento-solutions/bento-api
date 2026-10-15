package com.bento.crm.drive.controller;

import com.bento.crm.drive.dto.DriveDtos.PublicFile;
import com.bento.crm.drive.service.DriveService;
import com.bento.crm.file.model.StoredFile;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Shared files: the link token is the credential, and it expires. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Drive")
public class PublicFileController {

    private final DriveService drive;

    @GetMapping("/public/files/{token}")
    public PublicFile info(@PathVariable String token) {
        return drive.publicFile(token);
    }

    @GetMapping("/public/files/{token}/download")
    public ResponseEntity<Resource> download(@PathVariable String token) {
        Map.Entry<StoredFile, Resource> file = drive.publicDownload(token);
        return FileResponses.of(file.getKey(), file.getValue(), true);
    }
}
