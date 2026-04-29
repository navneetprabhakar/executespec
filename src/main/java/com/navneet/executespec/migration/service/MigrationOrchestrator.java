package com.navneet.executespec.migration.service;

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
import com.navneet.executespec.migration.entity.MigrationRecipe;
import com.navneet.executespec.migration.entity.MigrationSlot;
import com.navneet.executespec.migration.entity.MigrationVariant;
import com.navneet.executespec.migration.repository.MigrationRecipeRepository;
import com.navneet.executespec.migration.repository.MigrationSlotRepository;
import com.navneet.executespec.migration.repository.MigrationVariantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * R-339 — Phase 2 of the migration two-phase plan-gate (Apply path).
 *
 * <p>Wires the migration recipe library into the existing V2 +
 * Execution View pipeline:
 *
 * <ol>
 *   <li>Validate the user's slot values against the recipe's slot
 *       definitions ({@link SlotValidator}). Returns 400 with the
 *       full violation list when invalid.</li>
 *   <li>Pick the variant (by {@code optionsSignature} when supplied,
 *       else first variant — most recipes have a single variant
 *       since source-stack is fixed).</li>
 *   <li>Tier-gate check: caller below recipe.tier → 403.</li>
 *   <li>Compose the spec: substitute slot values into the variant's
 *       {@code spec_template} via {@link SpecComposer}.</li>
 *   <li>Build a {@link CreateExecutionRequest} with the composed
 *       spec as the requirement + selected repos + branch override.</li>
 *   <li>Call {@link ExecutionOrchestrator#createExecution(Project,
 *       AppUser, CreateExecutionRequest)} — the existing multi-repo
 *       Execution View handles parallel V2 dispatch per repo.</li>
 *   <li>Stamp {@code execution_run.migration_recipe_id} +
 *       {@code .migration_variant_id} for tracking + analytics.</li>
 *   <li>Trigger analysis ({@link ExecutionOrchestrator#triggerAnalysis(String)})
 *       to start the pipeline.</li>
 * </ol>
 *
 * <p>The cost-cap UX (FREE/PRO see a per-launch cap input;
 * TEAM/ENTERPRISE see info-only) lives in the controller and the
 * frontend; the existing V2 cost tracker enforces budget at
 * runtime via the credit system that's already in place.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MigrationOrchestrator {

    private final MigrationRecipeRepository recipeRepo;
    private final MigrationVariantRepository variantRepo;
    private final MigrationSlotRepository slotRepo;
    private final ExecutionOrchestrator executionOrchestrator;
    private final ExecutionRunRepository runRepo;
    private final ProjectRepository projectRepo;
    private final AppUserRepository userRepo;

    /**
     * Apply the recipe across the user's selected repos.  Returns the
     * created {@link ExecutionRun} so the controller can redirect the
     * caller to {@code /v3/runs/<runId>}.
     *
     * @throws ResponseStatusException 400 on validation errors,
     *         403 on tier-gate failure, 404 on missing recipe /
     *         project / user.
     */
    public ExecutionRun apply(String slug, ApplyRequest req, String callerUserId) {
        if (req == null || req.projectId() == null || req.projectId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectId is required");
        }
        if (req.repoIds() == null || req.repoIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "at least one repo must be selected");
        }

        MigrationRecipe recipe = recipeRepo.findBySlug(slug)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "recipe not found"));

        AppUser user = userRepo.findById(callerUserId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unknown user"));

        // 3. Tier-gate.
        if (isLockedFor(recipe, user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "This recipe requires the " + recipe.getTier().name() + " tier. "
                    + "Upgrade to apply.");
        }

        // 1. Validate slots.
        List<MigrationSlot> slotEntities = slotRepo.findByRecipeIdOrderByDisplayOrderAsc(recipe.getId());
        List<SlotValidator.SlotDef> slotDefs = slotEntities.stream()
            .map(s -> new SlotValidator.SlotDef(
                s.getName(), s.getDisplayName(),
                s.getType() == null ? "TEXT" : s.getType().name(),
                s.isRequired(), s.getOptions()))
            .toList();
        List<SlotValidator.Violation> violations =
            SlotValidator.validateAll(slotDefs, req.slotValues());
        if (!violations.isEmpty()) {
            String summary = violations.stream()
                .map(v -> v.slotName() + ": " + v.message())
                .reduce((a, b) -> a + "; " + b).orElse("");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "slot validation failed: " + summary);
        }

        // 2. Pick variant.
        MigrationVariant variant = pickVariant(recipe.getId(), req.optionsSignature());
        if (variant == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "no variant available for this recipe (catalog seed issue?)");
        }

        // 4. Compose spec.
        Map<String, String> defaults = new HashMap<>();
        for (MigrationSlot s : slotEntities) {
            if (s.getDefaultValue() != null && !s.getDefaultValue().isBlank()) {
                defaults.put(s.getName(), s.getDefaultValue());
            }
        }
        String composedSpec = SpecComposer.compose(
            variant.getSpecTemplate(),
            req.slotValues() == null ? Map.of() : req.slotValues(),
            defaults);

        // Stamp the recipe metadata into the requirement so a
        // human reviewer can trace "where did this PR come from?"
        // without joining tables.
        String requirement =
            "Migration recipe: " + recipe.getName() + " (slug=" + slug + ")\n"
                + "Variant: " + variant.getOptionsSignature() + "\n\n"
                + composedSpec;

        // 5+6. Build request + dispatch via ExecutionOrchestrator.
        Project project = projectRepo.findById(req.projectId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found"));

        // Convert UUID repoIds → String list for CreateExecutionRequest.
        List<String> repoIdStrs = req.repoIds().stream().map(UUID::toString).toList();

        CreateExecutionRequest createReq = new CreateExecutionRequest(
            requirement,
            repoIdStrs,
            null,                                           // newRepos
            false,                                          // jiraEnabled
            null,                                           // confluenceUrl
            null,                                           // figmaUrl
            null,                                           // aiProvider — use project default
            req.branch(),                                   // branch override (optional)
            null, null, null);                              // continue-on-PR fields

        ExecutionRun run = executionOrchestrator.createExecution(project, user, createReq);

        // 7. Stamp tracking columns.
        run.setMigrationRecipeId(recipe.getId());
        run.setMigrationVariantId(variant.getId());
        run = runRepo.save(run);

        // 8. Trigger analysis (the rest of the pipeline cascades from
        //    there — analysis → arch review → planning → executing).
        executionOrchestrator.triggerAnalysis(run.getId());

        log.info("[R-339] Migration apply: recipe={} variant={} executionRunId={} repos={}",
            slug, variant.getOptionsSignature(), run.getId(), req.repoIds().size());
        return run;
    }

    private MigrationVariant pickVariant(UUID recipeId, String optionsSignature) {
        if (optionsSignature != null && !optionsSignature.isBlank()) {
            Optional<MigrationVariant> exact = variantRepo
                .findByRecipeIdAndOptionsSignature(recipeId, optionsSignature);
            if (exact.isPresent()) return exact.get();
        }
        // Fall through to first variant (most recipes have only one).
        List<MigrationVariant> all = variantRepo.findByRecipeId(recipeId);
        return all.isEmpty() ? null : all.get(0);
    }

    private boolean isLockedFor(MigrationRecipe recipe, AppUser user) {
        if (recipe.getTier() == null) return false;
        MigrationRecipe.Tier required = recipe.getTier();
        MigrationRecipe.Tier actual = parseTier(user.getTier());
        return required.ordinal() > actual.ordinal();
    }

    private static MigrationRecipe.Tier parseTier(String raw) {
        if (raw == null) return MigrationRecipe.Tier.FREE;
        try {
            return MigrationRecipe.Tier.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return MigrationRecipe.Tier.FREE;
        }
    }

    /**
     * Apply request body.
     *
     * @param projectId           the user's project.
     * @param repoIds             selected repos to apply across.
     * @param slotValues          user-supplied slot values; null OK
     *                            for slot-less recipes.
     * @param optionsSignature    optional — when non-null, picks that
     *                            specific variant; otherwise first.
     * @param branch              optional per-launch branch override.
     */
    public record ApplyRequest(
        String projectId,
        List<UUID> repoIds,
        Map<String, String> slotValues,
        String optionsSignature,
        String branch
    ) {
        public ApplyRequest {
            if (slotValues == null) slotValues = Map.of();
            if (repoIds == null) repoIds = new ArrayList<>();
        }
    }
}
