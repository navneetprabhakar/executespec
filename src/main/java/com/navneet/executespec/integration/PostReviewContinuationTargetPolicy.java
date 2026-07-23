package com.navneet.executespec.integration;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.harness.TaskOutcome;

/** Selects the worker task that a measured post-review quality continuation should rerun. */
final class PostReviewContinuationTargetPolicy {

  private PostReviewContinuationTargetPolicy() {}

  static Optional<TaskOutcome> select(ExecutionResult execution, Plan plan) {
    if (execution == null || execution.perTask() == null || execution.perTask().isEmpty()) {
      return Optional.empty();
    }
    List<TaskOutcome> tasks = execution.perTask();
    Optional<TaskOutcome> latestUsable = Optional.empty();
    for (int index = tasks.size() - 1; index >= 0; index--) {
      TaskOutcome task = tasks.get(index);
      if (!isUsable(task)) continue;
      if (latestUsable.isEmpty()) latestUsable = Optional.of(task);
      if (!isDocumentationOnly(task, plan)) return Optional.of(task);
    }
    // Preserve a docs-only run while preventing a README tail task from absorbing a code-quality
    // continuation in a mixed plan.
    return latestUsable;
  }

  private static boolean isUsable(TaskOutcome task) {
    if (task == null || task.status() == null) return false;
    boolean successful =
        switch (task.status()) {
          case SUCCESS, SUCCESS_AFTER_RETRY, COMPLETED_WITH_WARNING -> true;
          case WORKER_FAILED, SKIPPED -> false;
        };
    return successful && hasText(task.dispatchedProvider()) && hasText(task.dispatchedModel());
  }

  private static boolean isDocumentationOnly(TaskOutcome outcome, Plan plan) {
    if (plan == null || plan.tasks() == null || !hasText(outcome.taskId())) return false;
    String taskId = outcome.taskId();
    String unprefixedId =
        taskId.length() > 9 && taskId.charAt(8) == '_' ? taskId.substring(9) : taskId;
    return plan.tasks().stream()
        .filter(task -> task != null && hasText(task.id()))
        .filter(task -> task.id().equals(taskId) || task.id().equals(unprefixedId))
        .findFirst()
        .map(
            task ->
                task.touchesFiles() != null
                    && !task.touchesFiles().isEmpty()
                    && task.touchesFiles().stream()
                        .allMatch(PostReviewContinuationTargetPolicy::isDocumentationPath))
        .orElse(false);
  }

  private static boolean isDocumentationPath(String path) {
    if (!hasText(path)) return false;
    String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
    return normalized.endsWith(".md")
        || normalized.endsWith(".mdx")
        || normalized.endsWith(".rst")
        || normalized.startsWith("docs/");
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
