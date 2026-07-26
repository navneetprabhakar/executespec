package com.navneet.executespec.agent;

import java.util.List;
import java.util.Locale;

/** Closes mechanical database driver/engine mismatches without selecting a new stack. */
final class PlanDatabaseStackReconciler {

  private PlanDatabaseStackReconciler() {}

  static Plan reconcile(Plan plan) {
    if (plan == null || plan.tasks() == null) return plan;
    List<PlannedTask> tasks =
        plan.tasks().stream().map(PlanDatabaseStackReconciler::reconcileTask).toList();
    return TaskExpansionPlanNormalizer.copyPlan(plan, tasks);
  }

  private static PlannedTask reconcileTask(PlannedTask task) {
    if (task == null) return null;
    String evidence = PlanSafetyEvidenceSupport.taskEvidence(task).toLowerCase(Locale.ROOT);
    if (!evidence.contains("create_async_engine")
        || !evidence.contains("sqlite:///")
        || evidence.contains("sqlite+aiosqlite:///")) {
      return task;
    }
    String description = asyncSqliteUrl(task.description());
    List<String> constraints =
        (task.hardConstraints() == null ? List.<String>of() : task.hardConstraints())
            .stream().map(PlanDatabaseStackReconciler::asyncSqliteUrl).toList();
    return TaskExpansionPlanNormalizer.copyTask(task, description, constraints);
  }

  private static String asyncSqliteUrl(String value) {
    return TaskExpanderAgent.safe(value)
        .replaceAll("(?i)(?<![a-z0-9+])sqlite:///", "sqlite+aiosqlite:///");
  }
}
