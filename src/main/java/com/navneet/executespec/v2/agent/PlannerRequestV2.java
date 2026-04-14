package com.navneet.executespec.v2.agent;

import com.navneet.executespec.v2.tool.ExecutionContext;
import lombok.Builder;

/**
 * Input to {@link PlannerAgentV2#plan(PlannerRequestV2)}.
 *
 * @param spec            the user's natural-language task specification
 * @param classification  SINGLE_SHOT / MEDIUM / MULTI_TASK. For Phase 4 this is
 *                        supplied by the caller; Phase 5 adds auto-classification
 *                        via triage.
 * @param provider        which LLM provider to use (ignored when classification
 *                        is SINGLE_SHOT since no LLM call is made)
 * @param model           which model to use (ignored for SINGLE_SHOT)
 * @param context         execution context for the planner call — used to scope
 *                        any filesystem inspection the planner does (Phase 4 is
 *                        pure generation; Phase 5 adds workspace-aware planning).
 * @param workspaceSummary optional one-line summary of the workspace state (e.g.
 *                        "empty directory" / "existing Spring Boot project / 45 files").
 *                        Injected into the planner prompt for context.
 */
@Builder
public record PlannerRequestV2(
        String spec,
        Classification classification,
        String provider,
        String model,
        ExecutionContext context,
        String workspaceSummary,
        String projectMemory) {

    public PlannerRequestV2 {
        if (spec == null || spec.isBlank()) throw new IllegalArgumentException("spec required");
        if (classification == null) classification = Classification.MEDIUM;
        if (context == null) throw new IllegalArgumentException("context required");
        // provider/model only required for non-SINGLE_SHOT
        if (classification != Classification.SINGLE_SHOT) {
            if (provider == null || provider.isBlank()) throw new IllegalArgumentException("provider required");
            if (model == null || model.isBlank()) throw new IllegalArgumentException("model required");
        }
    }
}
