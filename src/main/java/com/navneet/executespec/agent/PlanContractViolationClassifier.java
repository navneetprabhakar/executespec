package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.MAX_DESCRIPTION_CHARS;
import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Partitions the assembled-plan contract findings into the two classes the final compiler treats
 * differently (2026-08-08 plan-contract gate tiering; see {@code agent/CLAUDE.md}).
 *
 * <ul>
 *   <li><b>Structural</b> — findings that prove the plan cannot execute: a null/empty task list, a
 *       description that overflowed the worker-brief envelope after coherence, or a task carrying
 *       more hard constraints than the compiler emits. {@code TaskExecutionBriefCompiler} keeps
 *       these fail-closed because there is no way to hand a worker a truncated brief and call it a
 *       plan.
 *   <li><b>Advisory</b> — every other {@link TaskExpansionPlanContract#finalPlanContractViolations}
 *       finding (the heuristic {@link PlanContractQualityGate} battery plus the cross-task semantic
 *       detectors). These false-positive on legitimate plans often enough that a correct plan used
 *       to reach the gate only as a coin flip, so they are surfaced to the worker and reviewer as
 *       guidance rather than killing the run.
 * </ul>
 */
final class PlanContractViolationClassifier {
  private PlanContractViolationClassifier() {}

  static List<String> structural(Plan plan) {
    if (plan == null || plan.tasks() == null) return List.of("assembled plan has no tasks");
    List<String> violations = new ArrayList<>();
    for (PlannedTask task : plan.tasks()) {
      if (task == null) {
        violations.add("assembled plan contains a null task");
        continue;
      }
      if (safe(task.description()).length() > MAX_DESCRIPTION_CHARS) {
        violations.add(
            task.id()
                + ": description exceeds "
                + MAX_DESCRIPTION_CHARS
                + " characters after coherence");
      }
      if (task.hardConstraints() != null && task.hardConstraints().size() > 12) {
        violations.add(task.id() + ": hard constraints exceed 12 after coherence");
      }
    }
    return List.copyOf(new LinkedHashSet<>(violations));
  }

  static List<String> advisory(Plan plan) {
    List<String> structural = structural(plan);
    List<String> advisory = new ArrayList<>();
    for (String violation : TaskExpansionPlanContract.finalPlanContractViolations(plan)) {
      if (!structural.contains(violation)) advisory.add(violation);
    }
    return List.copyOf(advisory);
  }
}
