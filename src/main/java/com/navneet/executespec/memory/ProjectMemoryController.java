package com.navneet.executespec.memory;

import java.util.List;

import com.navneet.executespec.memory.entity.ProjectRunLedger;
import com.navneet.executespec.memory.repository.ProjectRunLedgerRepository;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/projects/{projectId}/memory")
@RequiredArgsConstructor
public class ProjectMemoryController {

  private final ProjectMemoryProvider memoryProvider;
  private final ProjectRunLedgerRepository ledgerRepo;
  private final MemoryCompactionService compactionService;
  private final com.navneet.executespec.auth.repository.ProjectRepository projectRepo;
  private final com.navneet.executespec.project.security.ProjectAccessGuard projectAccessGuard;

  @GetMapping
  public ResponseEntity<ProjectMemoryResponse> getMemory(
      @PathVariable String projectId,
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.navneet.executespec.auth.security.AppUserDetails userDetails) {
    projectAccessGuard.assertCanRead(projectId, userDetails);
    String context = memoryProvider.buildPlannerContext(projectId);
    var stats = memoryProvider.getStats(projectId);
    var recent =
        ledgerRepo.findTop10ByProjectIdAndCompactedIntoIsNullOrderByRunNumberDesc(projectId);
    return ResponseEntity.ok(new ProjectMemoryResponse(context, stats, recent));
  }

  @GetMapping("/ledger")
  public ResponseEntity<List<ProjectRunLedger>> getLedger(
      @PathVariable String projectId,
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.navneet.executespec.auth.security.AppUserDetails userDetails) {
    projectAccessGuard.assertCanRead(projectId, userDetails);
    return ResponseEntity.ok(ledgerRepo.findByProjectIdOrderByRunNumberDesc(projectId));
  }

  /** Force-compact all uncompacted ledger entries into a new snapshot. */
  @PostMapping("/compact")
  public ResponseEntity<java.util.Map<String, Object>> compact(
      @PathVariable String projectId,
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.navneet.executespec.auth.security.AppUserDetails userDetails) {
    projectAccessGuard.assertCanRead(projectId, userDetails);
    String orgId =
        projectRepo
            .findById(projectId)
            .map(com.navneet.executespec.auth.entity.Project::getOrgId)
            .orElse(null);
    int version = compactionService.compactNow(projectId, orgId);
    return ResponseEntity.ok(
        java.util.Map.of("snapshotVersion", version, "compacted", version > 0));
  }

  public record ProjectMemoryResponse(
      String fullContext,
      ProjectMemoryProvider.MemoryStats stats,
      List<ProjectRunLedger> recentEntries) {}
}
