package com.navneet.executespec.api.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * POST /api/runs request body.
 *
 * <p>Every run executes inside its own isolated workspace directory ({@code workspace/<runId>/}),
 * so there is no need for a "fresh workspace" flag — isolation is built-in.
 */
public record RunRequest(
    /**
     * The spec the user wants executed. Accepts the legacy field name {@code "requirement"} too —
     * docs and several external scripts historically used that name (adversarial-test-report
     * Finding 17).
     */
    @NotBlank(message = "User request must not be blank") @Size(max = 50_000, message = "User request must not exceed 50,000 characters") @JsonAlias({"requirement", "spec"})
        String userRequest,
    /** "MANUAL" | "GITHUB_NEW" | "GITHUB_EXISTING" */
    @Pattern(regexp = "MANUAL|GITHUB_NEW|GITHUB_EXISTING|REST", message = "Invalid source") String source,
    /**
     * HTTPS URL of an existing repo (optional). Restricted to HTTPS against the major SCM hosts so
     * the harness can't be tricked into fetching from the cloud-metadata endpoint, localhost, or a
     * {@code file://} URI. Closes adversarial-test-report Finding 7.
     */
    @Size(max = 500, message = "Repo URL must not exceed 500 characters") @Pattern(
            regexp =
                "^$|^https://(github\\.com|gitlab\\.com|bitbucket\\.org|[a-zA-Z0-9.-]+\\.githubusercontent\\.com)/.+$",
            message =
                "repoUrl must be an HTTPS URL against github.com, gitlab.com, or bitbucket.org")
        String repoUrl,
    /** "NEW" | "EXISTING" (optional). */
    @Pattern(regexp = "NEW|EXISTING", message = "Invalid repoMode") String repoMode,
    /** When true, automatically approve the plan gate without human review. */
    Boolean skipApproval,
    /** When true, automatically approve the merge gate without human review. */
    Boolean autoMerge,
    /** "PROTOTYPE" | "PRODUCTION" (default). Controls speed-vs-quality tradeoffs. */
    @Pattern(regexp = "PROTOTYPE|PRODUCTION", message = "Invalid executionMode") String executionMode,
    /** Optional model override applied to all agents (e.g. "claude-sonnet-4-5"). */
    @Size(max = 100, message = "Model name must not exceed 100 characters") String modelName,
    /**
     * Optional AI provider override for this run. v2.5.13 — enum renamed GOOGLE → GEMINI; legacy
     * GOOGLE still accepted.
     */
    @Pattern(regexp = "ANTHROPIC|GEMINI|GOOGLE|OPENAI|OPENROUTER", message = "Invalid AI provider") String aiProvider,
    /** Optional project ID to associate this run with (org members only). */
    @Size(max = 36, message = "Project ID must not exceed 36 characters") String projectId,
    /**
     * Pilot v3.0 — repos selected from the project's linked catalogue. When present, the harness
     * clones each repo into {@code workspace/<runId>/repos/<name>/}. When null or empty, falls back
     * to the single-repo {@link #repoUrl} path. IDs must reference repos already linked to {@link
     * #projectId}.
     */
    List<UUID> selectedRepoIds,
    /** Pilot R7 — optional Figma URL, drives design-compliance scoring. */
    @Size(max = 500, message = "Figma URL must not exceed 500 characters") String figmaUrl,
    /**
     * v2.5.2 (Phase 4.3) — when true, the planner is instructed to declare test-writing tasks as a
     * leading group with implementation tasks declaring dependsOn the test tasks. Same plan
     * structure, just different ordering. Higher accuracy on logic-heavy specs at the cost of more
     * tokens.
     */
    Boolean tddMode,
    /**
     * Fix M (2026-05-03) — incremental-update branch override. When set, every cloned repo is
     * checked out to this branch instead of its default ({@code git clone -b <branch>}); the
     * post-run push lands commits on this branch instead of creating a new {@code
     * executespec/<runIdShort>}. The frontend's {@code LaunchFromChatModal} sends this when the
     * user fills the per-launch branch input — previously the field was silently dropped because
     * the DTO didn't expose it. Also accepted as {@code branchOverride} for forward-compat with
     * external callers that prefer the longer name.
     *
     * <p>Use cases: (1) re-launch on an existing PR's head branch to address review comments; (2)
     * stack a staged launch onto an in-progress branch.
     */
    @Size(max = 200, message = "Branch must not exceed 200 characters") @JsonAlias({"branchOverride"})
        String branch,
    /**
     * Fix M (2026-05-03) — feed prior PR review comments into the planner. {@link
     * com.navneet.executespec.agent.PlannerRequest#prReviewComments} already plumbed this field
     * through to the {@code outline-planner.md} {@code {{PR_REVIEW_COMMENTS}}} placeholder;
     * exposing it via the public DTO lets external clients request a "address these review comments
     * on this PR" run without copy-pasting the comments into the spec body.
     *
     * <p>Free-form text — the planner treats it as advisory context, not as a hard task list.
     */
    @Size(max = 20_000, message = "PR review comments must not exceed 20,000 characters") String prReviewComments,
    /**
     * GA-readiness P3.2 (2026-05-06) — optional customer-supplied hard cost ceiling in USD. When
     * set, this overrides the platform-derived {@code hard_cap_usd} (credits × $5 × 1.5) with a
     * tighter ceiling. The {@code WorkerAgent} budget guard will force-terminate the run with
     * {@code Outcome.BUDGET_EXCEEDED} the moment cumulative spend reaches this value.
     *
     * <p>Capped at $1,000 by validation so a typo or copy-paste error can't accidentally authorise
     * an unbounded run. Min $0.10 — anything lower would prevent even a no-op run from finishing.
     *
     * <p>The platform cap (1.5× the credit-derived estimate) still applies as a CEILING — the
     * effective cap is {@code min(userCostCap, platformHardCap)}, computed in {@code
     * RunOrchestrator}. Customers can lower their ceiling but not raise it past what credits
     * already authorised.
     */
    @DecimalMin(value = "0.10", message = "userCostCap must be ≥ $0.10") @DecimalMax(value = "1000.00", message = "userCostCap must be ≤ $1000") Double userCostCap,
    /**
     * Audit r5 (#5, 2026-05-06) — staged-PR-before-push mode.
     *
     * <p>When true, the run executes normally but {@link
     * com.navneet.executespec.integration.RunOrchestrator} skips the auto-push to GitHub /
     * Bitbucket on success. The workspace stays on disk and the user can inspect the diff in the
     * Run Detail page; if they like it they can manually push via {@code POST
     * /runs/{id}/push-github} (the existing single-repo path). Default false → ships today's
     * auto-push behaviour.
     *
     * <p>The flag is transient: not persisted to {@code pipeline_run}. A JVM crash mid-run loses
     * the skip-push intent and the run resumes its default auto-push behaviour. That's intentional
     * for v1 — staging is a per-submit decision, not a project-wide setting.
     */
    Boolean skipAutoPush) {}
