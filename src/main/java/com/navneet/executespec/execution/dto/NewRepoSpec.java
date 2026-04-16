package com.navneet.executespec.execution.dto;

/**
 * User-supplied description of a fresh repo to create as part of a Launch.
 * The orchestrator creates the repo via GitHubRepoService, links it to the
 * project, and includes its UUID in the ExecutionRun alongside any existing
 * repo IDs. {@code techHint} must match one of the scaffold template keys
 * exposed by {@code /api/scaffolds}.
 */
public record NewRepoSpec(
        String name,
        String description,
        String techHint,
        String githubOwner,
        String visibility) {  // "private" | "public"
}
