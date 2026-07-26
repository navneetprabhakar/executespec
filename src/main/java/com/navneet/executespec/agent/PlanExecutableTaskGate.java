package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.List;
import java.util.Locale;

/** Rejects final executable tasks whose expander output is still blank or a fallback sentinel. */
final class PlanExecutableTaskGate {
  private static final String NO_DESCRIPTION_SENTINEL = "(no description)";

  private PlanExecutableTaskGate() {}

  static void addViolations(Plan plan, List<String> violations) {
    for (PlannedTask task : plan.tasks()) {
      if (task == null) continue;
      String description = safe(task.description()).trim();
      if (!description.isBlank()
          && !description.toLowerCase(Locale.ROOT).contains(NO_DESCRIPTION_SENTINEL)) {
        continue;
      }
      violations.add(
          task.id()
              + ": executable task has no expanded description; title, requirement IDs, file"
              + " names, and injected safety constraints are not sufficient worker instructions");
    }
  }
}
