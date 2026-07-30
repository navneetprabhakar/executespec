package com.navneet.executespec.reviewer;

import java.util.List;

import com.navneet.executespec.agent.RequirementSpec;
import com.navneet.executespec.tool.ExecutionContext;

import lombok.Builder;

/**
 * Input to {@link ReviewerAgent#review(ReviewerRequest)}.
 *
 * @param provider LLM provider for review calls
 * @param model LLM model for review calls
 * @param context execution context (workspace + ids); the reviewer substitutes its own task IDs for
 *     each review pass
 * @param buildCommand optional build command override — the reviewer runs this after fixes to
 *     verify nothing is broken. If null, BuildProjectTool falls back to framework inference.
 * @param maxIterationsPerPass override for worker iteration cap per review pass (null = default 30,
 *     smaller than worker's 40 since review passes are typically shorter)
 * @param skipWiringPass if true, only run the general review pass
 */
@Builder
public record ReviewerRequest(
    String provider,
    String model,
    ExecutionContext context,
    String buildCommand,
    Integer maxIterationsPerPass,
    Boolean skipWiringPass,
    Boolean brownfield,
    List<String> allowedRepoRoots,
    List<String> approvedNewFiles,
    Boolean allowBuildManifestCreation,
    /** True for a final correction attestation that must not mutate the reviewed workspace. */
    Boolean readOnly,
    String originalGoal,
    List<RequirementSpec> requirements) {

  public ReviewerRequest {
    if (provider == null || provider.isBlank())
      throw new IllegalArgumentException("provider required");
    if (model == null || model.isBlank()) throw new IllegalArgumentException("model required");
    if (context == null) throw new IllegalArgumentException("context required");
    if (skipWiringPass == null) skipWiringPass = false;
    if (brownfield == null) brownfield = false;
    if (allowedRepoRoots == null) allowedRepoRoots = List.of();
    if (approvedNewFiles == null) approvedNewFiles = List.of();
    if (allowBuildManifestCreation == null) allowBuildManifestCreation = false;
    if (readOnly == null) readOnly = false;
    if (requirements == null) requirements = List.of();
  }
}
