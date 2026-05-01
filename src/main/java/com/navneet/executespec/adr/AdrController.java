package com.navneet.executespec.adr;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * M-014 (Wave 2) — REST surface for ADRs cached from {@code
 * .executespec/arch/adrs/<NNNN>-<slug>.md} into the V137 {@code adr} table.
 *
 * <p>{@code GET /api/projects/{projectId}/adrs} — list ADRs for a project (optionally filter to
 * ACCEPTED — the planner-injection set). {@code GET /api/projects/{projectId}/adrs/{id}} — single
 * ADR with full body.
 *
 * <p>RBAC: project membership advisory in v1, matching the rest of the enterprise-readiness
 * branch's REST layer pattern (E1/E3 equivalents).
 */
@RestController
@RequestMapping("/api/projects/{projectId}/adrs")
@RequiredArgsConstructor
public class AdrController {

  private final AdrRepository adrRepo;

  @GetMapping
  public ResponseEntity<List<AdrSummary>> list(
      @PathVariable String projectId,
      @RequestParam(required = false, defaultValue = "false") boolean acceptedOnly,
      Authentication auth) {
    if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "auth");
    List<Adr> rows =
        acceptedOnly
            ? adrRepo.findAcceptedForProject(projectId)
            : adrRepo.findByProjectId(projectId);
    return ResponseEntity.ok(rows.stream().map(AdrSummary::from).toList());
  }

  @GetMapping("/{id}")
  public ResponseEntity<AdrDetail> get(
      @PathVariable String projectId, @PathVariable UUID id, Authentication auth) {
    if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "auth");
    Adr adr =
        adrRepo
            .findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ADR not found"));
    if (!projectId.equals(adr.getProjectId())) {
      // Don't leak cross-project IDs by returning a 200; pretend not-found.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ADR not found");
    }
    return ResponseEntity.ok(AdrDetail.from(adr));
  }

  /** Lightweight row for the list endpoint. */
  public record AdrSummary(
      String id,
      int adrIdInRepo,
      String title,
      String status,
      String decisionDate,
      String repoId,
      String filePath) {
    static AdrSummary from(Adr a) {
      return new AdrSummary(
          a.getId().toString(),
          a.getAdrIdInRepo(),
          a.getTitle(),
          a.getStatus(),
          a.getDecisionDate() == null ? null : a.getDecisionDate().toString(),
          a.getRepoId() == null ? null : a.getRepoId().toString(),
          a.getFilePath());
    }
  }

  /** Full record for the detail endpoint, including markdown body. */
  public record AdrDetail(
      String id,
      int adrIdInRepo,
      String title,
      String status,
      String decisionDate,
      String repoId,
      String filePath,
      String deciders,
      String supersedes,
      String supersededBy,
      String relatedAdrs,
      String relatedStandards,
      String tags,
      String body) {
    static AdrDetail from(Adr a) {
      return new AdrDetail(
          a.getId().toString(),
          a.getAdrIdInRepo(),
          a.getTitle(),
          a.getStatus(),
          a.getDecisionDate() == null ? null : a.getDecisionDate().toString(),
          a.getRepoId() == null ? null : a.getRepoId().toString(),
          a.getFilePath(),
          a.getDeciders(),
          a.getSupersedes(),
          a.getSupersededBy(),
          a.getRelatedAdrs(),
          a.getRelatedStandards(),
          a.getTags(),
          a.getBody());
    }
  }
}
