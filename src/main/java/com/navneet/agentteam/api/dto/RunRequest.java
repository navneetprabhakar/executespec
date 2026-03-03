package com.navneet.agentteam.api.dto;

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
    /** Optional model override applied to all agents (e.g. "claude-sonnet-4-5"). */
    String modelName
) {}
