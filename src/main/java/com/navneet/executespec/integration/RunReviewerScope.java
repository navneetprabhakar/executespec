package com.navneet.executespec.integration;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.project.service.ProjectRepoService;

/** Derives the repository and planned-file authority passed to final reviewer turns. */
final class RunReviewerScope {

  private RunReviewerScope() {}

  static List<String> repoRoots(
      ProjectRepoService projectRepoService, String projectId, List<UUID> selectedRepoIds) {
    if (projectId == null || selectedRepoIds == null || selectedRepoIds.isEmpty()) {
      return List.of();
    }
    return projectRepoService.resolveForRunStrict(projectId, selectedRepoIds).stream()
        .map(repo -> "repos/" + RunPipelineSupport.lastPathSegment(repo.getGithubRepoFullName()))
        .distinct()
        .toList();
  }

  static List<String> approvedNewFiles(Plan plan) {
    if (plan == null || plan.tasks() == null) return List.of();
    return plan.tasks().stream()
        .flatMap(
            task ->
                task.touchesFiles() == null
                    ? java.util.stream.Stream.empty()
                    : task.touchesFiles().stream())
        .filter(path -> path != null && !path.isBlank())
        .map(String::trim)
        .distinct()
        .toList();
  }
}
