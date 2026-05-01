package com.navneet.executespec.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.agent.model.TaskDefinition;
import com.navneet.executespec.agent.model.TaskPlan;
import com.navneet.executespec.eval.PlannerScaffoldTestCase.ScaffoldCheck;

/**
 * Validates a planner-generated TaskPlan against structural scaffold checks. Each check produces a
 * PASS/FAIL result with an explanation.
 */
public class PlannerScaffoldValidator {

  public record CheckResult(String name, boolean passed, String detail) {}

  public record ValidationResult(
      String scenarioId,
      String provider,
      String model,
      boolean planValidJson,
      int taskCount,
      List<String> taskIds,
      List<CheckResult> checks,
      String overall,
      long latencyMs,
      String rawProjectType,
      String error) {
    public static ValidationResult error(
        String scenarioId, String provider, String model, long latencyMs, String error) {
      return new ValidationResult(
          scenarioId,
          provider,
          model,
          false,
          0,
          List.of(),
          List.of(),
          "ERROR",
          latencyMs,
          null,
          error);
    }
  }

  public static List<CheckResult> validate(TaskPlan plan, PlannerScaffoldTestCase testCase) {
    List<CheckResult> results = new ArrayList<>();

    for (ScaffoldCheck check : testCase.checks()) {
      results.add(evaluateCheck(plan, check));
    }

    return results;
  }

  private static CheckResult evaluateCheck(TaskPlan plan, ScaffoldCheck check) {
    return switch (check.type()) {
      case PROJECT_TYPE -> checkProjectType(plan, check);
      case TASK_EXISTS -> checkTaskExists(plan, check);
      case TASK_NOT_EXISTS -> checkTaskNotExists(plan, check);
      case FULL_STACK_SPLIT -> checkFullStackSplit(plan, check);
      case FILE_IN_PLAN -> checkFileInPlan(plan, check);
      case MAX_FILES_PER_TASK -> checkMaxFilesPerTask(plan, check);
      case TASK_COMPLEXITY -> checkTaskComplexity(plan, check);
    };
  }

  private static CheckResult checkProjectType(TaskPlan plan, ScaffoldCheck check) {
    String actual = plan.getProjectType();
    boolean passed = check.value().equalsIgnoreCase(actual);
    return new CheckResult(
        check.name(),
        passed,
        passed
            ? "project_type=" + actual
            : "Expected project_type=" + check.value() + " but got " + actual);
  }

  private static CheckResult checkTaskExists(TaskPlan plan, ScaffoldCheck check) {
    boolean found = plan.getTasks().stream().anyMatch(t -> check.value().equals(t.getTaskId()));
    return new CheckResult(
        check.name(),
        found,
        found
            ? "Task '" + check.value() + "' exists"
            : "Task '" + check.value() + "' NOT found in plan");
  }

  private static CheckResult checkTaskNotExists(TaskPlan plan, ScaffoldCheck check) {
    boolean found = plan.getTasks().stream().anyMatch(t -> check.value().equals(t.getTaskId()));
    return new CheckResult(
        check.name(),
        !found,
        !found
            ? "Task '" + check.value() + "' correctly absent"
            : "Task '" + check.value() + "' should NOT exist but was found");
  }

  private static CheckResult checkFullStackSplit(TaskPlan plan, ScaffoldCheck check) {
    boolean hasBackend =
        plan.getTasks().stream().anyMatch(t -> "backend_setup".equals(t.getTaskId()));
    boolean hasFrontend =
        plan.getTasks().stream().anyMatch(t -> "frontend_setup".equals(t.getTaskId()));
    boolean hasSingleSetup =
        plan.getTasks().stream().anyMatch(t -> "project_setup".equals(t.getTaskId()));

    if (hasBackend && hasFrontend && !hasSingleSetup) {
      return new CheckResult(
          check.name(),
          true,
          "Correctly split: backend_setup + frontend_setup (no single project_setup)");
    }

    // Also accept if the planner used a single project_setup but it's MEDIUM+ complexity
    // and has ≤6 files — this is a softer pass
    if (hasSingleSetup) {
      Optional<TaskDefinition> setupTask =
          plan.getTasks().stream().filter(t -> "project_setup".equals(t.getTaskId())).findFirst();
      if (setupTask.isPresent()) {
        String complexity = setupTask.get().getComplexity();
        int fileCount =
            setupTask.get().getOwnedFiles() != null ? setupTask.get().getOwnedFiles().size() : 0;
        return new CheckResult(
            check.name(),
            false,
            "Single project_setup found (complexity="
                + complexity
                + ", files="
                + fileCount
                + ") — should be split into backend_setup + frontend_setup");
      }
    }

    StringBuilder detail = new StringBuilder("Unexpected structure: ");
    if (!hasBackend) detail.append("missing backend_setup; ");
    if (!hasFrontend) detail.append("missing frontend_setup; ");
    return new CheckResult(check.name(), false, detail.toString().trim());
  }

  private static CheckResult checkFileInPlan(TaskPlan plan, ScaffoldCheck check) {
    String fileSubstring = check.value();
    for (TaskDefinition task : plan.getTasks()) {
      if (task.getOwnedFiles() != null) {
        for (String file : task.getOwnedFiles()) {
          if (file.contains(fileSubstring)) {
            return new CheckResult(
                check.name(),
                true,
                "Found '"
                    + fileSubstring
                    + "' in task '"
                    + task.getTaskId()
                    + "' owned_files: "
                    + file);
          }
        }
      }
      // Also check shared_files
      if (task.getSharedFiles() != null) {
        for (String file : task.getSharedFiles()) {
          if (file.contains(fileSubstring)) {
            return new CheckResult(
                check.name(),
                true,
                "Found '"
                    + fileSubstring
                    + "' in task '"
                    + task.getTaskId()
                    + "' shared_files: "
                    + file);
          }
        }
      }
    }
    return new CheckResult(
        check.name(),
        false,
        "File matching '"
            + fileSubstring
            + "' NOT found in any task's owned_files or shared_files");
  }

  private static CheckResult checkMaxFilesPerTask(TaskPlan plan, ScaffoldCheck check) {
    int maxAllowed = Integer.parseInt(check.value());
    for (TaskDefinition task : plan.getTasks()) {
      int fileCount = task.getOwnedFiles() != null ? task.getOwnedFiles().size() : 0;
      if (fileCount > maxAllowed) {
        return new CheckResult(
            check.name(),
            false,
            "Task '"
                + task.getTaskId()
                + "' has "
                + fileCount
                + " owned_files (max "
                + maxAllowed
                + "): "
                + task.getOwnedFiles());
      }
    }
    return new CheckResult(check.name(), true, "All tasks within " + maxAllowed + "-file limit");
  }

  private static CheckResult checkTaskComplexity(TaskPlan plan, ScaffoldCheck check) {
    // value format: "task_id:EXPECTED_COMPLEXITY"
    String[] parts = check.value().split(":");
    String taskId = parts[0];
    String expectedComplexity = parts[1];

    Optional<TaskDefinition> task =
        plan.getTasks().stream().filter(t -> taskId.equals(t.getTaskId())).findFirst();

    if (task.isEmpty()) {
      return new CheckResult(
          check.name(), false, "Task '" + taskId + "' not found — cannot check complexity");
    }

    boolean passed = expectedComplexity.equalsIgnoreCase(task.get().getComplexity());
    return new CheckResult(
        check.name(),
        passed,
        passed
            ? "Task '" + taskId + "' complexity=" + task.get().getComplexity()
            : "Task '"
                + taskId
                + "' expected complexity="
                + expectedComplexity
                + " but got "
                + task.get().getComplexity());
  }
}
