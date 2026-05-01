package com.navneet.executespec.readymade.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.composer.SlotValidator;
import com.navneet.executespec.composer.SpecComposer;
import com.navneet.executespec.execution.dto.CreateExecutionRequest;
import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.execution.service.ExecutionOrchestrator;
import com.navneet.executespec.readymade.entity.ReadymadeSlot;
import com.navneet.executespec.readymade.entity.ReadymadeSolution;
import com.navneet.executespec.readymade.entity.ReadymadeVariant;
import com.navneet.executespec.readymade.repository.ReadymadeSlotRepository;
import com.navneet.executespec.readymade.repository.ReadymadeSolutionRepository;
import com.navneet.executespec.readymade.repository.ReadymadeVariantRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * R-331 — Readymades instantiate path. Mirrors {@link
 * com.navneet.executespec.migration.service.MigrationOrchestrator} so both products share the same
 * dispatch shape.
 *
 * <p>Steps on a {@code POST /api/library/{slug}/instantiate}:
 *
 * <ol>
 *   <li>Validate the user's slot values against the solution's slot definitions ({@link
 *       SlotValidator}). 400 with full violation list when invalid.
 *   <li>Pick the variant: explicit {@code variantId} when supplied; otherwise auto-resolve via
 *       {@link VariantResolver} on the first selected repo; otherwise the first variant.
 *   <li>Tier-gate check: caller below solution.tier → 403.
 *   <li>Compose the spec: substitute slot values into the variant's {@code spec_template} via
 *       {@link SpecComposer}.
 *   <li>Build a {@link CreateExecutionRequest} with the composed spec as the requirement + selected
 *       repos + branch override.
 *   <li>Dispatch via {@link ExecutionOrchestrator#createExecution(Project, AppUser,
 *       CreateExecutionRequest)} — existing multi-repo Execution View handles parallel V2 dispatch
 *       per repo.
 *   <li>Stamp {@code execution_run.readymade_solution_id} + {@code .readymade_variant_id} for
 *       tracking + analytics.
 *   <li>Trigger analysis to start the pipeline.
 * </ol>
 *
 * <p>Most readymades target a single repo; supporting multi-repo fan-out for free (via the same
 * Execution View used by Migrations) is a small win — power users who want to ship the same
 * readymade across, say, three frontend repos can do it in one launch.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReadymadeOrchestrator {

  private final ReadymadeSolutionRepository solutionRepo;
  private final ReadymadeVariantRepository variantRepo;
  private final ReadymadeSlotRepository slotRepo;
  private final VariantResolver variantResolver;
  private final ExecutionOrchestrator executionOrchestrator;
  private final ExecutionRunRepository runRepo;
  private final ProjectRepository projectRepo;
  private final AppUserRepository userRepo;

  public ExecutionRun instantiate(String slug, InstantiateRequest req, String callerUserId) {
    if (req == null || req.projectId() == null || req.projectId().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectId is required");
    }
    if (req.repoIds() == null || req.repoIds().isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "at least one repo must be selected");
    }

    ReadymadeSolution solution =
        solutionRepo
            .findBySlug(slug)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "solution not found"));

    AppUser user =
        userRepo
            .findById(callerUserId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unknown user"));

    if (isLockedFor(solution, user)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "This solution requires the " + solution.getTier().name() + " tier. Upgrade to use.");
    }

    // Validate slots.
    List<ReadymadeSlot> slotEntities =
        slotRepo.findBySolutionIdOrderByDisplayOrderAsc(solution.getId());
    List<SlotValidator.SlotDef> slotDefs =
        slotEntities.stream()
            .map(
                s ->
                    new SlotValidator.SlotDef(
                        s.getName(),
                        s.getDisplayName(),
                        s.getType() == null ? "TEXT" : s.getType().name(),
                        s.isRequired(),
                        s.getOptions()))
            .toList();
    List<SlotValidator.Violation> violations =
        SlotValidator.validateAll(slotDefs, req.slotValues());
    if (!violations.isEmpty()) {
      String summary =
          violations.stream()
              .map(v -> v.slotName() + ": " + v.message())
              .reduce((a, b) -> a + "; " + b)
              .orElse("");
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "slot validation failed: " + summary);
    }

    // Pick variant.
    ReadymadeVariant variant =
        pickVariant(solution.getId(), req.variantId(), req.projectId(), req.repoIds().get(0));
    if (variant == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "no variant available for this solution (catalog seed issue?)");
    }

    // Compose spec.
    Map<String, String> defaults = new HashMap<>();
    for (ReadymadeSlot s : slotEntities) {
      if (s.getDefaultValue() != null && !s.getDefaultValue().isBlank()) {
        defaults.put(s.getName(), s.getDefaultValue());
      }
    }
    String composedSpec =
        SpecComposer.compose(
            variant.getSpecTemplate(),
            req.slotValues() == null ? Map.of() : req.slotValues(),
            defaults);

    String requirement =
        "Readymade solution: "
            + solution.getName()
            + " (slug="
            + slug
            + ")\n"
            + "Variant: "
            + variant.getLanguage()
            + " · "
            + variant.getFramework()
            + "\n\n"
            + composedSpec;

    Project project =
        projectRepo
            .findById(req.projectId())
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found"));

    List<String> repoIdStrs = req.repoIds().stream().map(UUID::toString).toList();

    CreateExecutionRequest createReq =
        new CreateExecutionRequest(
            requirement,
            repoIdStrs,
            null, // newRepos
            false, // jiraEnabled
            null, // confluenceUrl
            null, // figmaUrl
            null, // aiProvider — use project default
            req.branch(), // branch override
            null,
            null,
            null); // continue-on-PR fields

    ExecutionRun run = executionOrchestrator.createExecution(project, user, createReq);

    // Stamp tracking columns.
    run.setReadymadeSolutionId(solution.getId());
    run.setReadymadeVariantId(variant.getId());
    run = runRepo.save(run);

    executionOrchestrator.triggerAnalysis(run.getId());

    log.info(
        "[R-331] Readymade instantiate: solution={} variant={}/{} executionRunId={} repos={}",
        slug,
        variant.getLanguage(),
        variant.getFramework(),
        run.getId(),
        req.repoIds().size());
    return run;
  }

  /**
   * Variant pick order: explicit id from the user → auto-resolve via stack detection → first
   * variant (most solutions have only one).
   */
  private ReadymadeVariant pickVariant(
      UUID solutionId, UUID variantId, String projectId, UUID firstRepoId) {
    if (variantId != null) {
      Optional<ReadymadeVariant> exact = variantRepo.findById(variantId);
      if (exact.isPresent() && exact.get().getSolutionId().equals(solutionId)) {
        return exact.get();
      }
    }
    // Auto-resolve via stack of the first selected repo.
    try {
      var resolution = variantResolver.resolve(solutionId, projectId, firstRepoId);
      if (resolution.bestMatch() != null) {
        return variantRepo.findById(resolution.bestMatch().id()).orElse(null);
      }
    } catch (Exception e) {
      log.debug("[R-331] auto-resolve failed for solution {}: {}", solutionId, e.getMessage());
    }
    // Fall through to first variant.
    List<ReadymadeVariant> all = variantRepo.findBySolutionId(solutionId);
    return all.isEmpty() ? null : all.get(0);
  }

  private boolean isLockedFor(ReadymadeSolution solution, AppUser user) {
    if (solution.getTier() == null) return false;
    ReadymadeSolution.Tier required = solution.getTier();
    ReadymadeSolution.Tier actual = parseTier(user.getTier());
    return required.ordinal() > actual.ordinal();
  }

  private static ReadymadeSolution.Tier parseTier(String raw) {
    if (raw == null) return ReadymadeSolution.Tier.FREE;
    try {
      return ReadymadeSolution.Tier.valueOf(raw.toUpperCase());
    } catch (IllegalArgumentException e) {
      return ReadymadeSolution.Tier.FREE;
    }
  }

  /**
   * Instantiate request body.
   *
   * @param projectId the user's project.
   * @param repoIds selected repos to apply across (most readymades target one).
   * @param slotValues user-supplied slot values.
   * @param variantId optional explicit variant pick; null → auto-resolve from the first repo's
   *     stack.
   * @param branch optional per-launch branch override.
   */
  public record InstantiateRequest(
      String projectId,
      List<UUID> repoIds,
      Map<String, String> slotValues,
      UUID variantId,
      String branch) {
    public InstantiateRequest {
      if (slotValues == null) slotValues = Map.of();
      if (repoIds == null) repoIds = new ArrayList<>();
    }
  }
}
