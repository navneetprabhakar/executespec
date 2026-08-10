package com.navneet.executespec.workspacegeneration;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** SUPER_ADMIN-only access to rejected candidate evidence; never a customer run-detail surface. */
@RestController
@RequestMapping("/api/admin/workspace-generation-attempts")
@RequiredArgsConstructor
public class WorkspaceGenerationQualificationEvidenceController {

  private final WorkspaceGenerationQualificationEvidenceService evidence;

  @GetMapping("/{attemptId}/qualification-evidence")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public ResponseEntity<WorkspaceGenerationQualificationEvidenceService.QualificationEvidence> read(
      @PathVariable UUID attemptId) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(evidence.read(attemptId));
  }
}
