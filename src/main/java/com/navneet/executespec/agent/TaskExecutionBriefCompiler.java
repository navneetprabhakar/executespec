package com.navneet.executespec.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles a bounded worker-visible task brief from the validated plan.
 *
 * <p>Requirements, hard constraints, and expected files are authoritative. The expanded description
 * remains implementation guidance because it carries the code-level sequencing and integration
 * details that do not fit cleanly into those structured fields.
 */
public final class TaskExecutionBriefCompiler {

  static final int MAX_BRIEF_CHARS = 12_000;

  private TaskExecutionBriefCompiler() {}

  /** Normalize, validate, and compile the final worker-visible plan or fail closed. */
  public static Plan compileExecutable(Plan plan) {
    return compileExecutable(plan, null);
  }

  /** Normalize and compile while preserving exact HTTP operations from the user's source spec. */
  public static Plan compileExecutable(Plan plan, String originalRequest) {
    if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) {
      throw contractFailure("planner returned an empty executable plan");
    }
    Plan compiled = compile(TaskExpanderAgent.normalizeFinalPlan(plan));
    List<String> violations = TaskExpanderAgent.finalPlanContractViolations(compiled);
    violations = new ArrayList<>(violations);
    violations.addAll(PlanSourceHttpRouteGate.violations(originalRequest, compiled));
    if (!violations.isEmpty()) throw contractFailure(String.join("; ", violations));
    return compiled;
  }

  public static Plan compile(Plan plan) {
    if (plan == null || plan.tasks() == null) return plan;
    Map<String, RequirementSpec> requirements = requirementIndex(plan.requirements());
    List<PlannedTask> tasks = new ArrayList<>(plan.tasks().size());
    for (PlannedTask task : plan.tasks()) {
      tasks.add(compileTask(task, requirements));
    }
    return Plan.builder()
        .planTitle(plan.planTitle())
        .planSummary(plan.planSummary())
        .projectType(plan.projectType())
        .buildCommand(plan.buildCommand())
        .smokeTestStrategy(plan.smokeTestStrategy())
        .runtimeSurface(plan.runtimeSurface())
        .tasks(List.copyOf(tasks))
        .classification(plan.classification())
        .requirements(plan.requirements())
        .build();
  }

  private static PlannedTask compileTask(
      PlannedTask task, Map<String, RequirementSpec> requirements) {
    if (task == null) throw contractFailure("assembled plan contains a null task");
    int authoritativeChars = authoritativeChars(task, requirements);
    if (authoritativeChars > MAX_BRIEF_CHARS) {
      throw contractFailure(
          task.id()
              + ": authoritative task contract exceeds "
              + MAX_BRIEF_CHARS
              + " characters; split the task or remove duplicated constraints");
    }

    String description = boundedDescription(task, MAX_BRIEF_CHARS - authoritativeChars);
    return PlannedTask.builder()
        .id(task.id())
        .title(task.title())
        .description(description)
        .complexity(task.complexity())
        .touchesFiles(task.touchesFiles())
        .dependsOn(task.dependsOn())
        .hardConstraints(task.hardConstraints())
        .meetsRequirements(task.meetsRequirements())
        .taskType(task.taskType())
        .build();
  }

  private static String boundedDescription(PlannedTask task, int availableChars) {
    String source =
        task.description() == null || task.description().isBlank()
            ? conciseDescription(task)
            : task.description().strip();
    int preservedChars = TaskExpansionTextNormalizer.preservedFinalContractChars(source);
    if (availableChars <= 0) {
      if (preservedChars > 0) {
        throw bindingDescriptionBudgetExceeded(task, availableChars);
      }
      return "";
    }
    if (source.length() > availableChars && preservedChars > availableChars) {
      throw bindingDescriptionBudgetExceeded(task, availableChars);
    }
    return TaskExpansionTextNormalizer.boundTextPreservingFinalContract(source, availableChars);
  }

  private static ExecutablePlanContractException bindingDescriptionBudgetExceeded(
      PlannedTask task, int availableChars) {
    return contractFailure(
        task.id()
            + ": explicitly binding description contracts exceed the remaining "
            + Math.max(0, availableChars)
            + "-character worker brief budget");
  }

  private static int authoritativeChars(
      PlannedTask task, Map<String, RequirementSpec> requirements) {
    int chars = task.title() == null ? 0 : task.title().length();
    List<String> requirementIds =
        task.meetsRequirements() == null ? List.of() : task.meetsRequirements();
    for (String id : requirementIds) {
      RequirementSpec requirement = requirements.get(id);
      if (requirement == null) {
        throw contractFailure(task.id() + ": unknown requirement " + id);
      }
      chars += id.length() + requirement.text().trim().length();
    }

    List<String> constraints = task.hardConstraints() == null ? List.of() : task.hardConstraints();
    for (String constraint : constraints) {
      if (constraint != null && !constraint.isBlank()) {
        chars += constraint.trim().length();
      }
    }
    for (String file : task.touchesFiles() == null ? List.<String>of() : task.touchesFiles()) {
      if (file != null && !file.isBlank()) chars += file.trim().length();
    }
    return chars;
  }

  private static String conciseDescription(PlannedTask task) {
    List<String> ids = task.meetsRequirements() == null ? List.of() : task.meetsRequirements();
    String assignment = ids.isEmpty() ? "the assigned requirement" : String.join(", ", ids);
    return "Implement "
        + assignment
        + " within this task's non-negotiable constraints and expected file scope.";
  }

  private static Map<String, RequirementSpec> requirementIndex(List<RequirementSpec> requirements) {
    Map<String, RequirementSpec> indexed = new LinkedHashMap<>();
    if (requirements == null) return indexed;
    for (RequirementSpec requirement : requirements) {
      if (requirement == null || requirement.id() == null || requirement.id().isBlank()) continue;
      RequirementSpec prior = indexed.putIfAbsent(requirement.id(), requirement);
      if (prior != null) {
        throw contractFailure("duplicate requirement id " + requirement.id());
      }
    }
    return indexed;
  }

  private static ExecutablePlanContractException contractFailure(String message) {
    return new ExecutablePlanContractException(message);
  }

  /**
   * A deterministic rejection of the assembled worker contract.
   *
   * <p>The orchestration boundary uses this type, rather than an exception-message prefix, to
   * distinguish a provider-produced contract defect from an internal compiler/runtime bug.
   */
  public static final class ExecutablePlanContractException extends IllegalStateException {
    private ExecutablePlanContractException(String message) {
      super(message);
    }
  }
}
