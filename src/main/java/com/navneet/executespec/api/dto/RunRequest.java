package com.navneet.executespec.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST /api/runs request body.
 *
 * <p>Every run executes inside its own isolated workspace directory
 * ({@code workspace/<runId>/}), so there is no need for a "fresh workspace" flag —
 * isolation is built-in.
 */
public record RunRequest(
    @NotBlank(message = "User request must not be blank")
    @Size(max = 50_000, message = "User request must not exceed 50,000 characters")
    String userRequest,
    /** "MANUAL" | "GITHUB_NEW" | "GITHUB_EXISTING" */
    @Pattern(regexp = "MANUAL|GITHUB_NEW|GITHUB_EXISTING|REST", message = "Invalid source")
    String source,
    /** HTTPS URL of an existing repo (optional). */
    @Size(max = 500, message = "Repo URL must not exceed 500 characters")
    String repoUrl,
    /** "NEW" | "EXISTING" (optional). */
    @Pattern(regexp = "NEW|EXISTING", message = "Invalid repoMode")
    String repoMode,
    /** When true, automatically approve the plan gate without human review. */
    Boolean skipApproval,
    /** When true, automatically approve the merge gate without human review. */
    Boolean autoMerge,
    /** "PROTOTYPE" | "PRODUCTION" (default). Controls speed-vs-quality tradeoffs. */
    @Pattern(regexp = "PROTOTYPE|PRODUCTION", message = "Invalid executionMode")
    String executionMode,
    /** Optional model override applied to all agents (e.g. "claude-sonnet-4-5"). */
    @Size(max = 100, message = "Model name must not exceed 100 characters")
    String modelName,
    /** Optional AI provider override for this run (ANTHROPIC, GOOGLE, OPENAI). Overrides project default. */
    @Pattern(regexp = "ANTHROPIC|GOOGLE|OPENAI", message = "Invalid AI provider")
    String aiProvider,
    /** Optional project ID to associate this run with (org members only). */
    @Size(max = 36, message = "Project ID must not exceed 36 characters")
    String projectId
) {}
