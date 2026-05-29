package com.navneet.executespec.readymade.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.readymade.dto.ReadymadeDetailDto;
import com.navneet.executespec.readymade.dto.ReadymadeListItemDto;
import com.navneet.executespec.readymade.dto.VariantResolutionDto;
import com.navneet.executespec.readymade.entity.ReadymadeFavourite;
import com.navneet.executespec.readymade.entity.ReadymadeSolution;
import com.navneet.executespec.readymade.entity.ReadymadeSolution.Tier;
import com.navneet.executespec.readymade.repository.ReadymadeFavouriteRepository;
import com.navneet.executespec.readymade.repository.ReadymadeSolutionRepository;
import com.navneet.executespec.readymade.service.ReadymadeOrchestrator;
import com.navneet.executespec.readymade.service.ReadymadeService;
import com.navneet.executespec.readymade.service.VariantResolver;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * R-329 — public read API for the Readymade Solutions library.
 *
 * <p>Endpoints:
 *
 * <ul>
 *   <li>{@code GET /api/library} — gallery list with filters.
 *   <li>{@code GET /api/library/{slug}} — detail for one solution.
 *   <li>{@code GET /api/library/categories} — facet counts.
 *   <li>{@code POST /api/library/{slug}/resolve} — pick the right variant for the user's repo
 *       (read-only computation).
 *   <li>{@code POST /api/library/{slug}/instantiate} — submit the slot-filled spec to the V2
 *       pipeline. <strong>Stubbed in W3 — returns 501;</strong> the SpecComposer + V2 wiring lands
 *       in W5.
 *   <li>{@code POST /api/library/favourite/{slug}} + {@code DELETE /api/library/favourite/{slug}} —
 *       per-user pin.
 * </ul>
 *
 * <p>Tier-gating is per-row in list/detail responses (lock icon + upgrade CTA derived client-side
 * from the {@code locked} + {@code unlockTier} fields).
 */
@RestController
@RequestMapping("/api/library")
@RequiredArgsConstructor
@Slf4j
public class ReadymadeController {

  private final ReadymadeService readymadeService;
  private final VariantResolver variantResolver;
  private final ReadymadeOrchestrator readymadeOrchestrator;
  private final ReadymadeFavouriteRepository favouriteRepo;
  private final ReadymadeSolutionRepository solutionRepo;
  private final ProjectAccessGuard projectAccessGuard;

  @GetMapping
  public List<ReadymadeListItemDto> list(
      @RequestParam(required = false) String category,
      @RequestParam(name = "macro_group", required = false) String macroGroup,
      @RequestParam(required = false) String tier,
      @RequestParam(required = false) String search,
      @AuthenticationPrincipal AppUserDetails userDetails) {

    return readymadeService.list(category, macroGroup, tier, search, callerTier(userDetails));
  }

  @GetMapping("/{slug}")
  public ReadymadeDetailDto detail(
      @PathVariable String slug, @AuthenticationPrincipal AppUserDetails userDetails) {
    return readymadeService
        .detail(slug, callerTier(userDetails))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "solution not found"));
  }

  @GetMapping("/categories")
  public Map<String, Object> facets() {
    return Map.of(
        "categories", readymadeService.categoryCounts(),
        "macroGroups", readymadeService.macroGroupCounts());
  }

  /**
   * Variant resolution for a (solution, project, repo) tuple. Read-only; no LLM call; safe to
   * invoke as the user types in the gallery's repo picker.
   */
  @PostMapping("/{slug}/resolve")
  public VariantResolutionDto resolve(
      @PathVariable String slug,
      @RequestParam("projectId") String projectId,
      @RequestParam("repoId") UUID repoId,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    // Tenant guard — projectId is caller-supplied; without this any authenticated user could
    // resolve a variant against another org's project/repo. Read-only. C2 — audit 2026-05-29.
    projectAccessGuard.assertCanRead(projectId, userDetails);
    ReadymadeSolution solution =
        solutionRepo
            .findBySlug(slug)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "solution not found"));
    return variantResolver.resolve(solution.getId(), projectId, repoId);
  }

  /**
   * R-331 — instantiate a solution into a V2 run. Validates slot values, picks the variant
   * (explicit > auto-resolve > first), composes the spec, and dispatches a multi-repo {@link
   * com.navneet.executespec.execution.entity.ExecutionRun} via the existing {@link
   * com.navneet.executespec.execution.service.ExecutionOrchestrator}.
   *
   * <p>The returned {@code executionRunId} is what the UI should navigate to (the run-detail
   * surface handles the rest of the pipeline lifecycle).
   */
  @PostMapping("/{slug}/instantiate")
  public ResponseEntity<Map<String, Object>> instantiate(
      @PathVariable String slug,
      @RequestBody ReadymadeOrchestrator.InstantiateRequest req,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    if (userDetails == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .body(Map.of("status", "unauthenticated"));
    }
    // Tenant guard — req.projectId() is caller-supplied; without this any authenticated user
    // could instantiate a run against another org's project (cross-org billing + repo writes).
    // C2 — audit 2026-05-29.
    projectAccessGuard.assertCanWrite(req == null ? null : req.projectId(), userDetails);
    ExecutionRun run = readymadeOrchestrator.instantiate(slug, req, userDetails.getUser().getId());
    log.info("[R-331] solution {} instantiated — executionRun {}", slug, run.getId());
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(
            Map.of(
                "executionRunId", run.getId(),
                "status", run.getStatus(),
                "message",
                    "Solution instantiated across "
                        + (req.repoIds() == null ? 0 : req.repoIds().size())
                        + " repo(s). Track progress on the run detail page."));
  }

  @PostMapping("/favourite/{slug}")
  public ResponseEntity<Void> favourite(
      @PathVariable String slug, @AuthenticationPrincipal AppUserDetails userDetails) {
    if (userDetails == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    ReadymadeSolution solution =
        solutionRepo
            .findBySlug(slug)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "solution not found"));
    String userId = userDetails.getUser().getId();
    if (!favouriteRepo.existsByUserIdAndSolutionId(userId, solution.getId())) {
      favouriteRepo.save(
          ReadymadeFavourite.builder().userId(userId).solutionId(solution.getId()).build());
    }
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/favourite/{slug}")
  public ResponseEntity<Void> unfavourite(
      @PathVariable String slug, @AuthenticationPrincipal AppUserDetails userDetails) {
    if (userDetails == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    ReadymadeSolution solution =
        solutionRepo
            .findBySlug(slug)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "solution not found"));
    favouriteRepo.deleteByUserIdAndSolutionId(userDetails.getUser().getId(), solution.getId());
    return ResponseEntity.noContent().build();
  }

  /** Caller's tier with FREE as the default for anonymous / missing. */
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
