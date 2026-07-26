package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.List;

/** Rejects visibly truncated or synthetic-compaction prose before it reaches a worker. */
final class PlanWorkerBriefIntegrityGate {
  private PlanWorkerBriefIntegrityGate() {}

  static void addViolations(Plan plan, List<String> violations) {
    if (plan == null || plan.tasks() == null) return;
    for (PlannedTask task : plan.tasks()) {
      if (task == null) continue;
      String description = safe(task.description());
      if (description.contains("[Contract-preserving compaction:")) {
        violations.add(
            task.id()
                + ": worker description contains a compaction omission marker; regenerate a"
                + " complete task body within the schema limit");
      }
      if (description.matches("(?s).*?(?:^|\\n)\\s*\\d+\\.\\s*$")) {
        violations.add(
            task.id()
                + ": worker description ends with an empty numbered step, indicating truncated"
                + " executable instructions");
      }
    }
  }
}
