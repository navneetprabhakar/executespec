package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.List;
import java.util.Locale;

/** Rejects a task that simultaneously requires production migrations and manual schema creation. */
final class PlanDatabaseInitializationContractGate {
  private PlanDatabaseInitializationContractGate() {}

  static void addViolations(Plan plan, List<String> violations) {
    if (plan == null || plan.tasks() == null) return;
    for (PlannedTask task : plan.tasks()) {
      if (task == null) continue;
      String evidence =
          (safe(task.description())
                  + "\n"
                  + String.join(
                      "\n", task.hardConstraints() == null ? List.of() : task.hardConstraints()))
              .toLowerCase(Locale.ROOT);
      boolean requiresManualSchema =
          evidence.matches(
              "(?s).*(?:base\\.metadata\\.create_all|orm\\s+create_all|"
                  + "manually\\s+create(?:s|d)?\\s+the\\s+schema).*");
      boolean forbidsManualSchema =
          evidence.matches(
              "(?s).*(?:must not|do not|never|forbid(?:s|den)?)"
                  + "[^.\\n]{0,100}(?:create_all|manual(?:ly)?\\s+(?:create|schema)).*");
      if (requiresManualSchema && forbidsManualSchema) {
        violations.add(
            task.id()
                + ": database initialization both requires and forbids manual ORM schema"
                + " creation; use the configured migration runner consistently");
      }
    }
  }
}
