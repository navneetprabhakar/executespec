package com.navneet.executespec.migration.controller;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.migration.dto.MigrationDetailDto;
import com.navneet.executespec.migration.dto.MigrationDryRunDto;
import com.navneet.executespec.migration.dto.MigrationListItemDto;
import com.navneet.executespec.migration.entity.MigrationRecipe.Tier;
import com.navneet.executespec.migration.service.MigrationDryRunService;
import com.navneet.executespec.migration.service.MigrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * R-338 — public read API for the Migration Recipes library, plus
 * the W3 two-phase plan-gate Phase 1 endpoint
 * ({@code POST /api/migrations/{slug}/dry-run}).
 *
 * <p>Phase 2 ({@code /apply}) is stubbed in W3 — returns 501.  The
 * V2 pipeline integration + slot validation + spec composition
 * land in W4-W5 of the migrations plan.
 */
@RestController
@RequestMapping("/api/migrations")
@RequiredArgsConstructor
@Slf4j
public class MigrationController {

    private final MigrationService migrationService;
    private final MigrationDryRunService dryRunService;

    @GetMapping
    public List<MigrationListItemDto> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String tier,
            @RequestParam(required = false) String search,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        return migrationService.list(category, tier, search, callerTier(userDetails));
    }

    @GetMapping("/{slug}")
    public MigrationDetailDto detail(@PathVariable String slug,
                                     @AuthenticationPrincipal AppUserDetails userDetails) {
        return migrationService.detail(slug, callerTier(userDetails))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "recipe not found"));
    }

    /**
     * Phase 1 of the two-phase halt-and-inspect plan-gate.  No LLM
     * call; safe to invoke on every page load of the recipe-detail
     * page.  Returns per-repo applicability + cost/file estimates +
     * sample diff (when cached).
     */
    @PostMapping("/{slug}/dry-run")
    public MigrationDryRunDto dryRun(@PathVariable String slug,
                                     @RequestBody DryRunRequest body) {
        if (body == null || body.projectId() == null || body.projectId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectId required");
        }
        return dryRunService.dryRun(slug, body.projectId(), body.repoIds());
    }

    /**
     * R-338 STUB — Phase 2 apply path.  Wired in W4 once
     * {@code MigrationOrchestrator} dispatches per-repo V2 runs
     * with slot-substituted specs.  Returns 501 so the UI can be
     * authored against the contract shape.
     */
    @PostMapping("/{slug}/apply")
    public ResponseEntity<Map<String, Object>> apply(@PathVariable String slug,
                                                     @RequestBody Map<String, Object> body) {
        log.info("[R-338] /api/migrations/{}/apply called — STUB (W4 wiring not yet shipped)", slug);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of(
            "status", "not_implemented",
            "message", "Phase-2 apply (multi-repo V2 dispatch + slot "
                + "substitution + cost-cap enforcement) ships in Wave 4 "
                + "of the Migrations plan. The contract shape is stable; "
                + "the implementation lands then.",
            "slug", slug
        ));
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
