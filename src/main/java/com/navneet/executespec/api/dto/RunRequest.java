package com.navneet.executespec.api.dto;

/**
 * POST /api/runs request body.
 *
 * <p>Every run executes inside its own isolated workspace directory
 * ({@code workspace/<runId>/}), so there is no need for a "fresh workspace" flag —
 * isolation is built-in.
 */
public record RunRequest(
    String userRequest,
    /** "MANUAL" | "GITHUB_NEW" | "GITHUB_EXISTING" */
    String source,
    /** HTTPS URL of an existing repo (optional). */
    String repoUrl,
    /** "NEW" | "EXISTING" (optional). */
    String repoMode,
    /** When true, automatically approve the plan gate without human review. */
    Boolean skipApproval,
    /** When true, automatically approve the merge gate without human review. */
    Boolean autoMerge,
    /** "PROTOTYPE" | "PRODUCTION" (default). Controls speed-vs-quality tradeoffs. */
    String executionMode,
    /** Optional model override applied to all agents (e.g. "claude-sonnet-4-5"). */
    String modelName,
    /** Optional AI provider override for this run (ANTHROPIC, GOOGLE, OPENAI). Overrides project default. */
    String aiProvider,
    /** Optional project ID to associate this run with (org members only). */
    String projectId
) {}
