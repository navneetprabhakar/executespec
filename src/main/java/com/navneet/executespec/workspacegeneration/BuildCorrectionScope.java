package com.navneet.executespec.workspacegeneration;

import java.util.TreeSet;

import com.navneet.executespec.agent.Plan;

/** Keeps every accepted task's required artifacts in a correction's frozen regression contract. */
public final class BuildCorrectionScope {
  private BuildCorrectionScope() {}

  public static GreenfieldBaselinePlan baseline(
      GreenfieldBaselinePlan source, Plan plan, int acceptedIndex) {
    if (plan == null
        || plan.tasks() == null
        || acceptedIndex < 0
        || acceptedIndex >= plan.tasks().size()) {
      throw new IllegalArgumentException("BUILD_CORRECTION_PREFIX_INVALID");
    }
    var paths = new TreeSet<>(source.requiredTaskPaths());
    plan.tasks()
        .subList(0, acceptedIndex + 1)
        .forEach(task -> paths.addAll(task.requiredArtifacts()));
    return new GreenfieldBaselinePlan(
        GreenfieldBaselinePlan.Kind.EXISTING,
        source.runtimeSurface(),
        false,
        source.supported(),
        source.filesToCreate(),
        java.util.List.copyOf(paths),
        source.initialFailureEvidence(),
        source.repositoryTargets());
  }
}
