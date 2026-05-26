package com.navneet.executespec.agent;

import com.navneet.executespec.tool.ExecutionContext;

import lombok.Builder;

/**
 * Input to {@link PlannerAgent#plan(PlannerRequest)}.
 *
 * @param spec the user's natural-language task specification
 * @param classification SINGLE_SHOT / MEDIUM / MULTI_TASK. For Phase 4 this is supplied by the
 *     caller; Phase 5 adds auto-classification via triage.
 * @param provider which LLM provider to use (ignored when classification is SINGLE_SHOT since no
 *     LLM call is made)
 * @param model which model to use (ignored for SINGLE_SHOT)
 * @param context execution context for the planner call — used to scope any filesystem inspection
 *     the planner does (Phase 4 is pure generation; Phase 5 adds workspace-aware planning).
 * @param workspaceSummary optional one-line summary of the workspace state (e.g. "empty directory"
 *     / "existing Spring Boot project / 45 files"). Injected into the planner prompt for context.
 */
@Builder
public record PlannerRequest(
    String spec,
    Classification classification,
    String provider,
    String model,
    ExecutionContext context,
    String workspaceSummary,
    String projectMemory,
    String projectBrief,
    String referenceDocs,
    /**
     * Enterprise-knowledge injection (flag {@code enterprise.inject.enabled}, default OFF) — the
     * rendered {@code {{CONTRACTS}}} block from {@link
     * com.navneet.executespec.contracts.injection.ContractInjectionService}. Nullable / blank when
     * no project or the flag is off; mirrors {@link #referenceDocs}.
     */
    String contracts,
    /**
     * Enterprise-knowledge injection (flag {@code enterprise.inject.enabled}, default OFF) — the
     * rendered {@code {{PROJECT_ADRS}}} block from {@link
     * com.navneet.executespec.adr.AdrInjectionService}. Nullable / blank when no project or the
     * flag is off; mirrors {@link #referenceDocs}.
     */
    String projectAdrs,
    /**
     * v2.5.2 (Phase 4.3) — when true, planner emits test tasks first with implementation tasks
     * declaring dependsOn the test ids.
     */
    boolean tddMode,
    /**
     * v2.5.7 — PR review comments from a prior run's PR. Injected into the planner prompt so it
     * plans tasks to address the feedback.
     */
    String prReviewComments) {

  public PlannerRequest {
    if (spec == null || spec.isBlank()) throw new IllegalArgumentException("spec required");
    if (classification == null) classification = Classification.MEDIUM;
    if (context == null) throw new IllegalArgumentException("context required");
    // provider/model only required for non-SINGLE_SHOT
    if (classification != Classification.SINGLE_SHOT) {
      if (provider == null || provider.isBlank())
        throw new IllegalArgumentException("provider required");
      if (model == null || model.isBlank()) throw new IllegalArgumentException("model required");
    }
  }
}
