package com.navneet.executespec.project.rag;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.rag.entity.ProjectDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/documents")
@RequiredArgsConstructor
public class ProjectDocumentController {

    private final ProjectDocumentService service;

    @PostMapping
    public ResponseEntity<ProjectDocument> upload(
            @AuthenticationPrincipal AppUserDetails principal,
            @PathVariable String projectId,
            @RequestPart("file") MultipartFile file) {
        String userId = principal != null ? principal.getUser().getId() : null;
        ProjectDocument doc = service.upload(projectId, file, userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(doc);
    }

    @GetMapping
    public ResponseEntity<List<ProjectDocument>> list(@PathVariable String projectId) {
        return ResponseEntity.ok(service.list(projectId));
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Map<String, String>> delete(
            @PathVariable String projectId,
            @PathVariable String documentId) {
        service.delete(projectId, documentId);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }
}
