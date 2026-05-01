package com.navneet.executespec.migration.service;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.migration.dto.MigrationDryRunDto;
import com.navneet.executespec.migration.dto.MigrationDryRunDto.RepoApplicability;
import com.navneet.executespec.migration.entity.MigrationDryRunCache;
import com.navneet.executespec.migration.entity.MigrationRecipe;
import com.navneet.executespec.migration.repository.MigrationDryRunCacheRepository;
import com.navneet.executespec.migration.repository.MigrationRecipeRepository;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * R-338 — Phase 1 of the migration two-phase plan-gate (halt-and-inspect).
 *
 * <p>Returns a {@link MigrationDryRunDto} for a (recipe, project, repos[]) tuple <strong>without
 * spending an LLM call</strong>:
 *
 * <ul>
 *   <li>Per-repo applicability check via {@link MigrationStackMatcher}.
 *   <li>Per-repo file count + cost band — sourced from {@link MigrationDryRunCache} when present,
 *       else from the recipe's declared {@code est_*} bands.
 *   <li>Sample diff — pulled from cache if present; null otherwise. Sample-diff <em>generation</em>
 *       is a single LLM call against one representative file; that lands in W3.5 / W4 alongside the
 *       apply path. In v1 dry-run never spends LLM on sample-diff.
 * </ul>
 *
 * <p>Sums per-repo bands into the totals on the DTO so the UI can surface "this migration will
 * touch ≈45 files across 3 repos for ≈$12-$30" on a single chip.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MigrationDryRunService {

  private final MigrationRecipeRepository recipeRepo;
  private final MigrationDryRunCacheRepository cacheRepo;
  private final ProjectRepoRepository projectRepoRepo;
  private final ProjectRepoLinkRepository linkRepo;

  /**
   * @param slug recipe slug. 404 if unknown.
   * @param projectId the project the user is launching from.
   * @param repoIds selected repos. Empty / null → empty perRepo.
   */
  public MigrationDryRunDto dryRun(String slug, String projectId, List<UUID> repoIds) {
    MigrationRecipe recipe =
        recipeRepo
            .findBySlug(slug)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "recipe not found"));

    List<RepoApplicability> perRepo = new ArrayList<>();
    BigDecimal costLowSum = BigDecimal.ZERO;
    BigDecimal costHighSum = BigDecimal.ZERO;
    int filesLowSum = 0;
    int filesHighSum = 0;
    boolean anyApplicable = false;
    String sampleDiff = null;

    if (repoIds != null) {
      for (UUID repoId : repoIds) {
        RepoApplicability row = evaluate(recipe, projectId, repoId);
        perRepo.add(row);

        if ("APPLICABLE".equals(row.status())) {
          anyApplicable = true;
          if (row.estCostLow() != null) costLowSum = costLowSum.add(row.estCostLow());
          if (row.estCostHigh() != null) costHighSum = costHighSum.add(row.estCostHigh());
          if (row.estFilesLow() != null) filesLowSum += row.estFilesLow();
          if (row.estFilesHigh() != null) filesHighSum += row.estFilesHigh();

          // Pull a sample diff from cache for the FIRST applicable
          // repo's stack signature; never recompute here.
          if (sampleDiff == null) {
            sampleDiff = lookupSampleDiff(recipe.getId(), repoId);
          }
        }
      }
    }

    return new MigrationDryRunDto(
        slug,
        recipe.getName(),
        anyApplicable ? costLowSum : null,
        anyApplicable ? costHighSum : null,
        anyApplicable ? filesLowSum : null,
        anyApplicable ? filesHighSum : null,
        sampleDiff,
        perRepo);
  }

  private RepoApplicability evaluate(MigrationRecipe recipe, String projectId, UUID repoId) {
    // Confirm the repo is linked to this project.
    if (linkRepo.findByProjectIdAndRepoId(projectId, repoId).isEmpty()) {
      return new RepoApplicability(
          repoId,
          null,
          "NOT_LINKED",
          "This repo isn't linked to the selected project.",
          null,
          null,
          null,
          null);
    }
    Optional<ProjectRepo> repoOpt = projectRepoRepo.findById(repoId);
    if (repoOpt.isEmpty()) {
      return new RepoApplicability(
          repoId, null, "NOT_LINKED", "Repo row not found.", null, null, null, null);
    }
    ProjectRepo repo = repoOpt.get();
    String fullName = repo.getGithubRepoFullName();

    MigrationStackMatcher.Outcome outcome =
        MigrationStackMatcher.match(recipe.getSourceStack(), repo.getDetectedStack());

    switch (outcome) {
      case INCOMPATIBLE_STACK -> {
        return new RepoApplicability(
            repoId,
            fullName,
            "INCOMPATIBLE_STACK",
            "This recipe doesn't match the repo's detected stack. "
                + "Pick a different recipe or analyse the repo first.",
            null,
            null,
            null,
            null);
      }
      case UNKNOWN_STACK -> {
        return new RepoApplicability(
            repoId,
            fullName,
            "UNKNOWN_STACK",
            "We haven't detected the repo's stack yet — analyse it "
                + "first, then re-run the dry run.",
            null,
            null,
            null,
            null);
      }
      case APPLICABLE -> {
        // For v1 we use the recipe's declared band.  Per-repo
        // bands from migration_dry_run_cache override when
        // present (cached after a real run lands).
        Optional<MigrationDryRunCache> cached =
            cacheRepo.findByRecipeIdAndStackSignature(
                recipe.getId(), stackSignature(repo.getDetectedStack()));

        Integer filesLow =
            cached.map(MigrationDryRunCache::getEstFilesLow).orElse(recipe.getEstFilesLow());
        Integer filesHigh =
            cached.map(MigrationDryRunCache::getEstFilesHigh).orElse(recipe.getEstFilesHigh());
        BigDecimal costLow =
            cached.map(MigrationDryRunCache::getEstCostLow).orElse(recipe.getEstCreditsLow());
        BigDecimal costHigh =
            cached.map(MigrationDryRunCache::getEstCostHigh).orElse(recipe.getEstCreditsHigh());

        return new RepoApplicability(
            repoId, fullName, "APPLICABLE", null, filesLow, filesHigh, costLow, costHigh);
      }
    }
    // Unreachable — exhaustive switch on enum.
    return new RepoApplicability(
        repoId, fullName, "UNKNOWN_STACK", "Unknown applicability state.", null, null, null, null);
  }

  private String lookupSampleDiff(UUID recipeId, UUID repoId) {
    Optional<ProjectRepo> repoOpt = projectRepoRepo.findById(repoId);
    if (repoOpt.isEmpty()) return null;
    return cacheRepo
        .findByRecipeIdAndStackSignature(recipeId, stackSignature(repoOpt.get().getDetectedStack()))
        .map(MigrationDryRunCache::getSampleDiff)
        .orElse(null);
  }

  /**
   * v1 stack signature: SHA-256 prefix of the detected_stack JSON (truncated to 32 hex chars).
   * Captures any change to the stack blob; v2 should refine to "major-version-only" so minor /
   * patch deltas don't invalidate cached samples.
   */
  static String stackSignature(String detectedStackJson) {
    if (detectedStackJson == null || detectedStackJson.isBlank()) return "unknown";
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(detectedStackJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (int i = 0; i < 16; i++) {
        hex.append(String.format("%02x", hash[i]));
      }
      return hex.toString();
    } catch (Exception e) {
      return "unknown";
    }
  }
}
