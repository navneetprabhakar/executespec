package com.navneet.executespec.arch.invariants;

import java.time.Instant;
import java.util.List;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * M-015 (Wave 2) — REST surface for V138 architectural invariants.
 *
 * <p>Customer git is source-of-truth for the per-repo {@code .executespec/arch/invariants.yaml}
 * files; this controller exposes the cached entries + recorded violations so the project dashboard
 * can render them without round-tripping to GitHub.
 *
 * <p>Two endpoints under {@code /api/projects/{projectId}/invariants}:
 *
 * <ul>
 *   <li>{@code GET /} — list every cached invariant for the project with status (ACTIVE / RETIRED).
 *   <li>{@code GET /violations} — recent violations across all invariants in the project, ordered
 *       newest-first.
 * </ul>
 */
@RestController
@RequestMapping("/api/projects/{projectId}/invariants")
@RequiredArgsConstructor
public class InvariantController {

  private final ArchInvariantRepository invRepo;
  private final ArchInvariantViolationRepository violationRepo;
  private final ProjectAccessGuard projectAccessGuard;

  @GetMapping
  public ResponseEntity<List<InvariantSummary>> list(
      @PathVariable String projectId, Authentication auth) {
    if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "auth");
    // GA-readiness P4.1-followup — invariants leak architectural intent + the
    // YAML body, so 403 across orgs.
    projectAccessGuard.assertCanRead(projectId, principalOrNull(auth));
    return ResponseEntity.ok(
        invRepo.findByProjectId(projectId).stream().map(InvariantSummary::from).toList());
  }

  @GetMapping("/violations")
  public ResponseEntity<List<ViolationDto>> recentViolations(
      @PathVariable String projectId, Authentication auth) {
    if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "auth");
    projectAccessGuard.assertCanRead(projectId, principalOrNull(auth));
    return ResponseEntity.ok(
        violationRepo.findRecentForProject(projectId).stream().map(ViolationDto::from).toList());
  }

  /** Extract {@link AppUserDetails} from {@link Authentication}; null when principal is wrong type. */
  private static AppUserDetails principalOrNull(Authentication auth) {
    if (auth == null) return null;
    Object principal = auth.getPrincipal();
    return principal instanceof AppUserDetails ud ? ud : null;
  }

  /**
   * Cached invariant — yaml body included so the panel can show the raw definition (admins read it;
   * planners feed it back into context).
   */
  public record InvariantSummary(
      String id, String invariantId, String status, String invariantYaml, Instant createdAt) {
    static InvariantSummary from(ArchInvariant i) {
      return new InvariantSummary(
          i.getId().toString(),
          i.getInvariantId(),
          i.getStatus(),
          i.getInvariantYaml(),
          i.getCreatedAt());
    }
  }

  /**
   * Recorded violation — phase distinguishes plan-time checks from post-merge
   * architecture-doc-regen continuous checks.
   */
  public record ViolationDto(
      String id, String invariantUuid, Instant detectedAt, String phase, String detail) {
    static ViolationDto from(ArchInvariantViolation v) {
      return new ViolationDto(
          v.getId().toString(),
          v.getInvariantId() == null ? null : v.getInvariantId().toString(),
          v.getDetectedAt(),
          v.getDetectionPhase(),
          v.getDetail());
    }
  }
}
