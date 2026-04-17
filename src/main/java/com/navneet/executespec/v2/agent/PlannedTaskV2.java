package com.navneet.executespec.v2.agent;

import lombok.Builder;

import java.util.List;

/**
 * A single task in a V2 {@link PlanV2}.
 *
 * <p>No {@code ownedFiles} contract as in V1 — V2 is sequential, so one agent has full
 * codebase visibility and the cross-task ownership walls are meaningless. The planner
 * still declares the files a task is expected to touch ({@link #touchesFiles}) as a
 * non-binding hint for the worker's upfront context fill (Phase 4 writes the field;
 * Phase 3's worker ignores it today — wiring lands in the executor in Phase 5).
 *
 * <p>{@link #dependsOn} references IDs of prior tasks that must complete first. In
 * V2 execution is strictly sequential in plan order, so this is documentation —
 * the executor processes tasks top-to-bottom. The field exists so future enhancements
 * (e.g. selective re-execution for task retries) have the information.
 *
 * @param id               unique within the plan (e.g. {@code "T1"}, {@code "T2"})
 * @param title            short one-line title for UI and logs
 * @param description      full task description — becomes the user prompt to the worker
 * @param complexity       LOW | MEDIUM | HIGH — used to size iteration budgets
 * @param touchesFiles     files the planner expects this task to read/write (hint)
 * @param dependsOn        prior task IDs that must run before this one
 * @param hardConstraints  per-task constraints the worker MUST respect
 *                         (e.g. "Do NOT use JPA — use JDBC directly"). Injected into
 *                         the worker system prompt verbatim.
 */
@Builder
public record PlannedTaskV2(
        String id,
        String title,
        String description,
        Complexity complexity,
        List<String> touchesFiles,
        List<String> dependsOn,
        List<String> hardConstraints,
        /** v2.5.11 (Validation v3 / Pillar 1) — requirement IDs from
         *  {@link PlanV2#requirements()} that this task is expected to implement.
         *  Post-parse validation guarantees every declared requirement is covered by
         *  at least one task and every entry here references a real requirement.
         *  Empty on legacy plans and when the v3 flag is off. */
        List<String> meetsRequirements) {

    public PlannedTaskV2 {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title required");
        if (description == null || description.isBlank()) throw new IllegalArgumentException("description required");
        if (complexity == null) complexity = Complexity.MEDIUM;
        if (touchesFiles == null) touchesFiles = List.of();
        if (dependsOn == null) dependsOn = List.of();
        if (hardConstraints == null) hardConstraints = List.of();
        if (meetsRequirements == null) meetsRequirements = List.of();
    }

    public enum Complexity { LOW, MEDIUM, HIGH }
}
