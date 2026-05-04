package com.navneet.executespec.v2.agent;

import java.util.List;

import lombok.Builder;

/**
 * A single task in a V2 {@link Plan}.
 *
 * <p>No {@code ownedFiles} contract as in V1 — V2 is sequential, so one agent has full codebase
 * visibility and the cross-task ownership walls are meaningless. The planner still declares the
 * files a task is expected to touch ({@link #touchesFiles}) as a non-binding hint for the worker's
 * upfront context fill (Phase 4 writes the field; Phase 3's worker ignores it today — wiring lands
 * in the executor in Phase 5).
 *
 * <p>{@link #dependsOn} references IDs of prior tasks that must complete first. In V2 execution is
 * strictly sequential in plan order, so this is documentation — the executor processes tasks
 * top-to-bottom. The field exists so future enhancements (e.g. selective re-execution for task
 * retries) have the information.
 *
 * @param id unique within the plan (e.g. {@code "T1"}, {@code "T2"})
 * @param title short one-line title for UI and logs
 * @param description full task description — becomes the user prompt to the worker
 * @param complexity LOW | MEDIUM | HIGH — used to size iteration budgets
 * @param touchesFiles files the planner expects this task to read/write (hint)
 * @param dependsOn prior task IDs that must run before this one
 * @param hardConstraints per-task constraints the worker MUST respect (e.g. "Do NOT use JPA — use
 *     JDBC directly"). Injected into the worker system prompt verbatim.
 */
@Builder
public record PlannedTask(
    String id,
    String title,
    String description,
    Complexity complexity,
    List<String> touchesFiles,
    List<String> dependsOn,
    List<String> hardConstraints,
    /**
     * v2.5.11 (Validation v3 / Pillar 1) — requirement IDs from {@link Plan#requirements()} that
     * this task is expected to implement. Post-parse validation guarantees every declared
     * requirement is covered by at least one task and every entry here references a real
     * requirement. Empty on legacy plans and when the v3 flag is off.
     */
    List<String> meetsRequirements,
    /**
     * v2.6.2-track slice 3 — LEAF (terminal task, executor runs as-is) vs EPIC (expand into
     * subtasks via {@code TaskExpanderAgent} recursion, depth ≤ 2). Default LEAF for
     * backward-compatibility with slice 1+2 plans (which predate the field). EPIC is only emitted
     * by skeleton-mode outlines on LARGE/LEGACY classifications.
     */
    TaskType taskType) {

  public PlannedTask {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
    if (title == null || title.isBlank()) throw new IllegalArgumentException("title required");
    // v2.6.2 slice 3 — skeleton-mode outlines emit blank descriptions; the
    // expander fills them later. Allow blank but never null so downstream
    // .replace() / .contains() calls don't NPE.
    if (description == null) description = "";
    if (complexity == null) complexity = Complexity.MEDIUM;
    if (touchesFiles == null) touchesFiles = List.of();
    if (dependsOn == null) dependsOn = List.of();
    if (hardConstraints == null) hardConstraints = List.of();
    if (meetsRequirements == null) meetsRequirements = List.of();
    if (taskType == null) taskType = TaskType.LEAF;
  }

  public enum Complexity {
    LOW,
    MEDIUM,
    HIGH
  }

  /**
   * Slice-3 task shape marker. EPIC tasks emit further subtasks via {@code TaskExpanderAgent}
   * (recursive). LEAF tasks ship straight to the executor.
   */
  public enum TaskType {
    LEAF,
    EPIC
  }

  /**
   * True when the task body still needs an expansion pass — i.e. the planner emitted a skeleton in
   * slice-3 LARGE mode and {@code description}/ {@code hardConstraints}/{@code meetsRequirements}
   * are still blank.
   */
  public boolean isSkeleton() {
    return (description == null || description.isBlank())
        && (hardConstraints == null || hardConstraints.isEmpty())
        && (meetsRequirements == null || meetsRequirements.isEmpty());
  }
}
