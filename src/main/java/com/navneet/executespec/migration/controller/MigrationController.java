package com.navneet.executespec.migration.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.migration.dto.MigrationDetailDto;
import com.navneet.executespec.migration.dto.MigrationDryRunDto;
import com.navneet.executespec.migration.dto.MigrationListItemDto;
import com.navneet.executespec.migration.entity.MigrationRecipe.Tier;
import com.navneet.executespec.migration.service.MigrationDryRunService;
import com.navneet.executespec.migration.service.MigrationOrchestrator;
import com.navneet.executespec.migration.service.MigrationService;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * R-338 — public read API for the Migration Recipes library, plus the W3 two-phase plan-gate Phase
 * 1 endpoint ({@code POST /api/migrations/{slug}/dry-run}).
 *
 * <p>Phase 2 ({@code /apply}) is stubbed in W3 — returns 501. The V2 pipeline integration + slot
 * validation + spec composition land in W4-W5 of the migrations plan.
 */
@RestController
@RequestMapping("/api/migrations")
@RequiredArgsConstructor
@Slf4j
public class MigrationController {

  private final MigrationService migrationService;
  private final MigrationDryRunService dryRunService;
  private final MigrationOrchestrator migrationOrchestrator;
  private final ProjectAccessGuard projectAccessGuard;

  @GetMapping
  public List<MigrationListItemDto> list(
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String tier,
      @RequestParam(required = false) String search,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    return migrationService.list(category, tier, search, callerTier(userDetails));
  }

  @GetMapping("/{slug}")
  public MigrationDetailDto detail(
      @PathVariable String slug, @AuthenticationPrincipal AppUserDetails userDetails) {
    return migrationService
        .detail(slug, callerTier(userDetails))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "recipe not found"));
  }

  /**
   * Phase 1 of the two-phase halt-and-inspect plan-gate. No LLM call; safe to invoke on every page
   * load of the recipe-detail page. Returns per-repo applicability + cost/file estimates + sample
   * diff (when cached).
   *
   * <p>[F-05] (audit Sprint 2, 2026-04-30) — tier gate added. The audit found that dry-run had no
   * tier check while apply did, so a FREE user could compute applicability + cost + sample diff for
   * any locked recipe (e.g. the TEAM-locked Mongoose→Prisma migration) without paying. Dry-run is
   * the buying signal — leaking it is the classic SaaS-buyer mistake. Now mirrors apply's tier
   * gate: 403 when the caller's tier is below the recipe's required tier, with the same
   * upgrade-to-tier message shape MigrationOrchestrator already returns.
   */
  @PostMapping("/{slug}/dry-run")
  public MigrationDryRunDto dryRun(
      @PathVariable String slug,
      @RequestBody DryRunRequest body,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    if (body == null || body.projectId() == null || body.projectId().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectId required");
    }
    // Tenant guard — projectId is caller-supplied; without this any authenticated user could
    // compute applicability + cost + sample diff for another org's project. Read-only.
    // C2 — audit 2026-05-29.
    projectAccessGuard.assertCanRead(body.projectId(), userDetails);
    // [F-05] tier gate — refuse dry-run on locked recipes.
    Tier required =
        migrationService
            .requiredTierFor(slug)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "recipe not found"));
    Tier caller = callerTier(userDetails);
    if (MigrationService.isLocked(required, caller)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "This migration recipe requires the " + required.name() + " tier.");
    }
    return dryRunService.dryRun(slug, body.projectId(), body.repoIds());
  }

  /**
   * R-339 — Phase 2 apply path. Validates slot values, picks the variant, composes the spec, and
   * dispatches a multi-repo ExecutionRun via the existing {@link
   * com.navneet.executespec.execution.service.ExecutionOrchestrator}.
   *
   * <p>The returned {@code executionRunId} is what the UI should navigate to (the existing
   * run-detail surface handles the rest of the pipeline lifecycle).
   */
  @PostMapping("/{slug}/apply")
  public ResponseEntity<Map<String, Object>> apply(
      @PathVariable String slug,
      @RequestBody MigrationOrchestrator.ApplyRequest req,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    if (userDetails == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("status", "unauthenticated"));
    }
    // Tenant guard — req.projectId() is caller-supplied; without this any authenticated user
    // could apply a migration run against another org's project (cross-org billing + repo writes).
    // C2 — audit 2026-05-29.
    projectAccessGuard.assertCanWrite(req == null ? null : req.projectId(), userDetails);
    ExecutionRun run = migrationOrchestrator.apply(slug, req, userDetails.getUser().getId());
    log.info("[R-339] migration {} applied — executionRun {}", slug, run.getId());
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(
            Map.of(
                "executionRunId", run.getId(),
                "status", run.getStatus(),
                "message",
                    "Migration started across "
                        + (req.repoIds() == null ? 0 : req.repoIds().size())
                        + " repo(s). Track progress on the run detail page."));
  }

  /** Request body for {@link #dryRun}. */
  public record DryRunRequest(String projectId, List<UUID> repoIds) {}

  private static Tier callerTier(AppUserDetails userDetails) {
    if (userDetails == null) return Tier.FREE;
    String raw = userDetails.getUser().getTier();
    if (raw == null) return Tier.FREE;
    try {
      return Tier.valueOf(raw.toUpperCase());
    } catch (IllegalArgumentException e) {
      return Tier.FREE;
    }
  }
}
