package com.navneet.executespec.project.rag;

import java.util.List;
import java.util.Map;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.rag.entity.ProjectDocument;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/projects/{projectId}/documents")
@RequiredArgsConstructor
public class ProjectDocumentController {

  private final ProjectDocumentService service;
  private final ProjectAccessGuard projectAccessGuard;

  @PostMapping
  public ResponseEntity<ProjectDocument> upload(
      @AuthenticationPrincipal AppUserDetails principal,
      @PathVariable String projectId,
      @RequestPart("file") MultipartFile file) {
    // GA-readiness P4.1-followup — RAG document upload writes to project state.
    // RL-2026-05-040: write guard (was assertCanRead → cross-org viewer could upload).
    projectAccessGuard.assertCanWrite(projectId, principal);
    String userId = principal != null ? principal.getUser().getId() : null;
    ProjectDocument doc = service.upload(projectId, file, userId);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(doc);
  }

  @GetMapping
  public ResponseEntity<List<ProjectDocument>> list(
      @AuthenticationPrincipal AppUserDetails principal, @PathVariable String projectId) {
    // GA-readiness P4.1-followup — document list exposes filenames + sizes
    // tied to the project; tenant-bound.
    projectAccessGuard.assertCanRead(projectId, principal);
    return ResponseEntity.ok(service.list(projectId));
  }

  @DeleteMapping("/{documentId}")
  public ResponseEntity<Map<String, String>> delete(
      @AuthenticationPrincipal AppUserDetails principal,
      @PathVariable String projectId,
      @PathVariable String documentId) {
    // GA-readiness P4.1-followup — destructive op; verify tenant before delete.
    // RL-2026-05-040: write guard (was assertCanRead).
    projectAccessGuard.assertCanWrite(projectId, principal);
    service.delete(projectId, documentId);
    return ResponseEntity.ok(Map.of("status", "deleted"));
  }
}
