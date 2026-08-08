package com.navneet.executespec.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

/**
 * Compiles a bounded worker-visible task brief from the validated plan.
 *
 * <p>Requirements, hard constraints, and expected files are authoritative. The expanded description
 * remains implementation guidance because it carries the code-level sequencing and integration
 * details that do not fit cleanly into those structured fields.
 */
@Slf4j
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

    // FAIL-CLOSED set — the plan cannot execute or violates explicit user intent. These stay fatal:
    //  * structural envelope overflow (a truncated brief is not a plan),
    //  * requirement ownership (execution authority — every requirement must be owned; this has a
    //    deterministic upstream backfill, so reaching here means a genuinely uncovered
    // requirement),
    //  * source HTTP route drift (adding/removing an /api namespace the user did not ask for).
    List<String> fatal =
        new ArrayList<>(TaskExpanderAgent.structuralPlanContractViolations(compiled));
    fatal.addAll(PlannerOutputUtils.validateRequirementCoverage(compiled));
    fatal.addAll(PlanSourceHttpRouteGate.violations(originalRequest, compiled));
    if (!fatal.isEmpty()) throw contractFailure(String.join("; ", fatal));

    // ADVISORY set — the heuristic natural-language contradiction detectors (the ~30-gate
    // PlanContractQualityGate battery plus the cross-task semantic checks). These are genuine
    // cross-task guidance but false-positive on legitimate plans often enough that a correct plan
    // used to fail to reach the plan gate as a coin flip (and, worse, fought the deterministic
    // safety enricher's own injected phrasing). BUILD-mode quality is enforced at execution time by
    // the worker's own tests and the read-only reviewer — the same mechanism that lets this system
    // pass coding benchmarks, which bypass this battery entirely. So surface these findings to the
    // worker as guidance and log them, but never kill the run over them.
    List<String> advisory = TaskExpanderAgent.advisoryPlanContractViolations(compiled);
    if (!advisory.isEmpty()) {
      log.warn(
          "Executable plan compiled with {} advisory contract finding(s) (non-blocking, surfaced to"
              + " the worker): {}",
          advisory.size(),
          boundedSummary(advisory));
      compiled = attachAdvisoryGuidance(compiled, advisory);
    }
    return compiled;
  }

  private static String boundedSummary(List<String> findings) {
    String joined = String.join(" | ", findings);
    return joined.length() > 600 ? joined.substring(0, 600) + " …(truncated)" : joined;
  }

  /**
   * Appends each advisory finding to its owning task's hard constraints as explicit non-blocking
   * guidance, so the worker actually sees the cross-task consistency note instead of it being lost
   * when the gate stops being fatal. Attachment is budget-safe: a task already at the constraint
   * count or brief-size ceiling is skipped (the finding was still logged), never truncated into a
   * structural violation. Findings with no resolvable owning task are left to the log.
   */
  private static Plan attachAdvisoryGuidance(Plan plan, List<String> advisory) {
    Map<String, List<String>> byTask = new LinkedHashMap<>();
    for (String finding : advisory) {
      int colon = finding.indexOf(": ");
      if (colon <= 0) continue;
      String taskId = finding.substring(0, colon).trim();
      String message = finding.substring(colon + 2).trim();
      if (taskId.isEmpty() || message.isEmpty()) continue;
      byTask.computeIfAbsent(taskId, ignored -> new ArrayList<>()).add(message);
    }
    if (byTask.isEmpty()) return plan;
    List<PlannedTask> rebuilt = new ArrayList<>(plan.tasks().size());
    for (PlannedTask task : plan.tasks()) {
      List<String> notes = task == null ? null : byTask.get(task.id());
      if (task == null || notes == null) {
        rebuilt.add(task);
        continue;
      }
      List<String> constraints =
          new ArrayList<>(task.hardConstraints() == null ? List.of() : task.hardConstraints());
      int briefUsed =
          safeLen(task.description())
              + constraints.stream().mapToInt(TaskExecutionBriefCompiler::safeLen).sum();
      for (String note : notes) {
        if (constraints.size() >= 12) break; // structural ceiling — do not overflow it
        String guidance = "ADVISORY (non-blocking): " + boundedNote(note);
        if (briefUsed + guidance.length() > MAX_BRIEF_CHARS - 500) break; // keep brief headroom
        constraints.add(guidance);
        briefUsed += guidance.length();
      }
      rebuilt.add(task.toBuilder().hardConstraints(constraints).build());
    }
    return plan.toBuilder().tasks(rebuilt).build();
  }

  private static int safeLen(String value) {
    return value == null ? 0 : value.length();
  }

  private static String boundedNote(String note) {
    return note.length() > 240 ? note.substring(0, 240) + " …" : note;
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
