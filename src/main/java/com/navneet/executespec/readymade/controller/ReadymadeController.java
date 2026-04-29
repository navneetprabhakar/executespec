package com.navneet.executespec.readymade.controller;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.readymade.dto.ReadymadeDetailDto;
import com.navneet.executespec.readymade.dto.ReadymadeListItemDto;
import com.navneet.executespec.readymade.dto.VariantResolutionDto;
import com.navneet.executespec.readymade.entity.ReadymadeFavourite;
import com.navneet.executespec.readymade.entity.ReadymadeSolution;
import com.navneet.executespec.readymade.entity.ReadymadeSolution.Tier;
import com.navneet.executespec.readymade.repository.ReadymadeFavouriteRepository;
import com.navneet.executespec.readymade.repository.ReadymadeSolutionRepository;
import com.navneet.executespec.readymade.service.ReadymadeService;
import com.navneet.executespec.readymade.service.VariantResolver;
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
 * R-329 — public read API for the Readymade Solutions library.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/library} — gallery list with filters.</li>
 *   <li>{@code GET  /api/library/{slug}} — detail for one solution.</li>
 *   <li>{@code GET  /api/library/categories} — facet counts.</li>
 *   <li>{@code POST /api/library/{slug}/resolve} — pick the right
 *       variant for the user's repo (read-only computation).</li>
 *   <li>{@code POST /api/library/{slug}/instantiate} — submit the
 *       slot-filled spec to the V2 pipeline. <strong>Stubbed in W3
 *       — returns 501;</strong> the SpecComposer + V2 wiring lands
 *       in W5.</li>
 *   <li>{@code POST /api/library/favourite/{slug}} +
 *       {@code DELETE /api/library/favourite/{slug}} — per-user pin.</li>
 * </ul>
 *
 * <p>Tier-gating is per-row in list/detail responses (lock icon +
 * upgrade CTA derived client-side from the {@code locked} +
 * {@code unlockTier} fields).
 */
@RestController
@RequestMapping("/api/library")
@RequiredArgsConstructor
@Slf4j
public class ReadymadeController {

    private final ReadymadeService readymadeService;
    private final VariantResolver variantResolver;
    private final ReadymadeFavouriteRepository favouriteRepo;
    private final ReadymadeSolutionRepository solutionRepo;

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
    public ReadymadeDetailDto detail(@PathVariable String slug,
                                     @AuthenticationPrincipal AppUserDetails userDetails) {
        return readymadeService.detail(slug, callerTier(userDetails))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "solution not found"));
    }

    @GetMapping("/categories")
    public Map<String, Object> facets() {
        return Map.of(
            "categories", readymadeService.categoryCounts(),
            "macroGroups", readymadeService.macroGroupCounts()
        );
    }

    /**
     * Variant resolution for a (solution, project, repo) tuple.
     * Read-only; no LLM call; safe to invoke as the user types in
     * the gallery's repo picker.
     */
    @PostMapping("/{slug}/resolve")
    public VariantResolutionDto resolve(
            @PathVariable String slug,
            @RequestParam("projectId") String projectId,
            @RequestParam("repoId") UUID repoId) {
        ReadymadeSolution solution = solutionRepo.findBySlug(slug)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "solution not found"));
        return variantResolver.resolve(solution.getId(), projectId, repoId);
    }

    /**
     * R-329 STUB — instantiate a solution into a V2 run.  Wired
     * properly in W5 after SlotValidator + SpecComposer ship + the
     * V158 {@code pipeline_run.readymade_*} columns land.  Returns
     * 501 in v1 so the UI can be authored against the contract
     * shape without us pretending the pipeline integration works.
     */
    @PostMapping("/{slug}/instantiate")
    public ResponseEntity<Map<String, Object>> instantiate(
            @PathVariable String slug,
            @RequestBody Map<String, Object> body) {
        log.info("[R-329] /api/library/{}/instantiate called — STUB (W5 wiring not yet shipped)", slug);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of(
            "status", "not_implemented",
            "message", "Slot validation + spec composition + V2 pipeline wiring "
                + "ship in Wave 5 of the Readymade Solutions plan. The contract "
                + "shape is stable; the implementation lands then.",
            "slug", slug
        ));
    }

    @PostMapping("/favourite/{slug}")
    public ResponseEntity<Void> favourite(@PathVariable String slug,
                                          @AuthenticationPrincipal AppUserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        ReadymadeSolution solution = solutionRepo.findBySlug(slug)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "solution not found"));
        String userId = userDetails.getUser().getId();
        if (!favouriteRepo.existsByUserIdAndSolutionId(userId, solution.getId())) {
            favouriteRepo.save(ReadymadeFavourite.builder()
                .userId(userId)
                .solutionId(solution.getId())
                .build());
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/favourite/{slug}")
    public ResponseEntity<Void> unfavourite(@PathVariable String slug,
                                            @AuthenticationPrincipal AppUserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        ReadymadeSolution solution = solutionRepo.findBySlug(slug)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "solution not found"));
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
