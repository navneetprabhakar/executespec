package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates that an executable journey prepares the cardinality it later requires. */
final class PlanJourneyCardinalityGate {
  private static final Pattern REQUIRED_CARDINALITY =
      Pattern.compile("(?i)\\bat\\s+least\\s+(\\d+)\\s+different\\s+([a-z][a-z0-9_-]*)");

  private PlanJourneyCardinalityGate() {}

  static void addViolations(Plan plan, List<String> violations) {
    if (plan == null || plan.tasks() == null) return;
    for (PlannedTask task : plan.tasks()) {
      if (task == null || !isJourneyTask(task)) continue;
      String description = safe(task.description());
      Matcher required = REQUIRED_CARDINALITY.matcher(description);
      while (required.find()) {
        int count = Integer.parseInt(required.group(1));
        if (count < 2) continue;
        String plural = required.group(2).toLowerCase(Locale.ROOT);
        String singular = PlanApiContractEvidence.singular(plural);
        String setup = description.substring(0, required.start()).toLowerCase(Locale.ROOT);
        int explicitCreates = 0;
        Matcher creates =
            Pattern.compile(
                    "\\bcreate(?:s|d|ing)?\\s+(?:a|an|one)(?:\\s+(?:second|third|fourth))?\\s+"
                        + Pattern.quote(singular)
                        + "\\b")
                .matcher(setup);
        while (creates.find()) explicitCreates++;
        boolean bulkSetup =
            setup.matches(
                "(?s).*\\bcreate(?:s|d|ing)?\\s+(?:at\\s+least\\s+)?"
                    + count
                    + "\\s+(?:different\\s+)?"
                    + Pattern.quote(plural)
                    + "\\b.*");
        if (explicitCreates >= count || bulkSetup) continue;
        violations.add(
            task.id()
                + ": journey requires at least "
                + count
                + " different "
                + plural
                + " but its preceding setup creates only "
                + explicitCreates);
      }
    }
  }

  private static boolean isJourneyTask(PlannedTask task) {
    String identity =
        (safe(task.title())
                + " "
                + String.join(" ", task.touchesFiles() == null ? List.of() : task.touchesFiles()))
            .toLowerCase(Locale.ROOT);
    return identity.matches("(?s).*\\b(?:e2e|end-to-end|acceptance|playwright|journey)\\b.*");
  }
}
