package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanSafetyContractEnricher.*;
import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.*;
import static com.navneet.executespec.agent.PlanSafetyStructureSupport.*;
import static com.navneet.executespec.agent.PlanSafetyTextSupport.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

final class PlanSafetyStructureSupport {

  private PlanSafetyStructureSupport() {}

  static PlannedTask withDescriptionConstraintsAndFiles(
      PlannedTask task, String description, List<String> constraints, List<String> touchesFiles) {
    if (description.equals(task.description())
        && constraints.equals(task.hardConstraints())
        && touchesFiles.equals(task.touchesFiles())) {
      return task;
    }
    return PlannedTask.builder()
        .id(task.id())
        .title(task.title())
        .description(description)
        .complexity(task.complexity())
        .touchesFiles(List.copyOf(new LinkedHashSet<>(touchesFiles)))
        .dependsOn(task.dependsOn())
        .hardConstraints(List.copyOf(constraints))
        .meetsRequirements(task.meetsRequirements())
        .taskType(task.taskType())
        .build();
  }

  static PlannedTask withDependencies(PlannedTask task, List<String> dependencies) {
    return PlannedTask.builder()
        .id(task.id())
        .title(task.title())
        .description(task.description())
        .complexity(task.complexity())
        .touchesFiles(task.touchesFiles())
        .dependsOn(List.copyOf(new LinkedHashSet<>(dependencies)))
        .hardConstraints(task.hardConstraints())
        .meetsRequirements(task.meetsRequirements())
        .taskType(task.taskType())
        .build();
  }

  static Plan withTasks(
      Plan plan, List<PlannedTask> tasks, String buildCommand, String projectType) {
    return Plan.builder()
        .planTitle(plan.planTitle())
        .planSummary(plan.planSummary())
        .projectType(projectType)
        .buildCommand(buildCommand)
        .smokeTestStrategy(plan.smokeTestStrategy())
        .runtimeSurface(plan.runtimeSurface())
        .tasks(List.copyOf(tasks))
        .classification(plan.classification())
        .requirements(plan.requirements())
        .build();
  }

  static int addConstraint(
      List<String> constraints,
      String constraint,
      String taskId,
      String reason,
      List<String> summaries) {
    if (constraints.contains(constraint)) return 0;
    constraints.add(constraint);
    summaries.add(taskId + " hardConstraint += '" + truncate(constraint, 60) + "' · " + reason);
    return 1;
  }

  static int removeConstraints(
      List<String> constraints,
      Predicate<String> predicate,
      String taskId,
      String reason,
      List<String> summaries) {
    int before = constraints.size();
    constraints.removeIf(predicate);
    int removed = before - constraints.size();
    if (removed > 0) summaries.add(taskId + " removed " + removed + " constraint(s) · " + reason);
    return removed;
  }

  static boolean isBackendImplementationConstraint(String constraint) {
    String normalized = safe(constraint).toLowerCase(Locale.ROOT);
    return containsAny(
        normalized,
        "sqlalchemy",
        "alembic",
        "sqlite",
        "create_all",
        "migration mechanism",
        "test fixtures must apply",
        "fresh database",
        "production server",
        "idempotencyrecord",
        "hybrid_property",
        "select for update",
        "begin immediate",
        "through the public api",
        "second cancellation must return",
        "stock must remain restored",
        "reservation accounting leaves");
  }

  static boolean isOrderReservationConstraint(String constraint) {
    String normalized = safe(constraint).toLowerCase(Locale.ROOT);
    return containsAny(
        normalized, "reservation quantity", "order.status", "order status", "stock >= quantity");
  }

  static boolean permitsKnownFailingAcceptance(String constraint) {
    String normalized = safe(constraint).toLowerCase(Locale.ROOT);
    return normalized.contains("test may fail until")
        || normalized.contains("tests may fail until")
        || normalized.contains("do not rewrite sibling-owned production logic")
        || (normalized.contains("do not implement production code") && normalized.contains("test"));
  }

  static boolean isRemovalMetaInstruction(String constraint) {
    String normalized = safe(constraint).strip().toLowerCase(Locale.ROOT);
    return normalized.startsWith("remove this constraint from ")
        || normalized.startsWith("delete this constraint from ");
  }

  static boolean meetsAnyRequirement(PlannedTask task, String... requirementIds) {
    if (task.meetsRequirements() == null) return false;
    for (String requirementId : requirementIds) {
      if (task.meetsRequirements().contains(requirementId)) return true;
    }
    return false;
  }

  static String canonicalBuildCommand(String command, String requirements) {
    if (command == null || command.isBlank()) return command;
    String normalized = command.toLowerCase(Locale.ROOT);
    if (normalized.contains("&&")
        || normalized.contains(";")
        || normalized.contains("|")
        || normalized.startsWith("cd ")
        || normalized.contains(" pytest")
        || normalized.contains("vitest")
        || normalized.contains("playwright")) {
      return null;
    }
    java.util.regex.Matcher requiredPython =
        java.util.regex.Pattern.compile("(?i)python\\s+(\\d+\\.\\d+)\\s*\\+?")
            .matcher(requirements);
    if (!requiredPython.find()) return command;
    String executable = "python" + requiredPython.group(1);
    return command.replaceAll("(?<![\\w.])python3?(?=\\s+-m\\b)", executable);
  }

  static String canonicalProjectType(Plan plan) {
    if (plan == null || plan.tasks() == null) return plan == null ? null : plan.projectType();
    boolean pythonBackend =
        plan.tasks().stream()
            .filter(Objects::nonNull)
            .map(PlanSafetyEvidenceSupport::taskEvidence)
            .anyMatch(
                evidence ->
                    containsAny(
                        evidence, "fastapi", "sqlalchemy", "pyproject.toml", "requirements.txt"));
    if (pythonBackend && "node-backend".equals(plan.projectType())) return "python";
    return plan.projectType();
  }

  static boolean hasMixedDatabaseSessionModes(List<PlannedTask> tasks) {
    if (tasks == null) return false;
    boolean async = false;
    boolean synchronous = false;
    for (PlannedTask task : tasks) {
      if (task == null || isFrontendOnlyTask(task)) continue;
      String evidence = taskEvidence(task);
      async |=
          containsAny(
              evidence, "asyncengine", "async engine", "async_sessionmaker", "asyncsession");
      synchronous |=
          containsAny(
              evidence,
              "synchronous session",
              "db: session",
              "sqlalchemy.orm.session",
              "sessionmaker factory");
    }
    return async && synchronous;
  }

  static boolean hasCanonicalAsyncDatabaseContract(List<PlannedTask> tasks) {
    if (tasks == null) return false;
    return tasks.stream()
        .filter(Objects::nonNull)
        .filter(task -> !isFrontendOnlyTask(task))
        .filter(
            task ->
                task.touchesFiles() != null
                    && task.touchesFiles().stream()
                        .filter(Objects::nonNull)
                        .map(path -> path.replace('\\', '/').toLowerCase(Locale.ROOT))
                        .anyMatch(path -> path.endsWith("/database.py")))
        .map(PlanSafetyEvidenceSupport::taskEvidence)
        .anyMatch(
            evidence ->
                containsAny(
                    evidence,
                    "create_async_engine",
                    "async_sessionmaker",
                    "asyncsession",
                    "async engine"));
  }

  static boolean requiresRealHttpAcceptance(List<PlannedTask> tasks) {
    if (tasks == null) return false;
    return tasks.stream()
        .filter(Objects::nonNull)
        .map(
            task ->
                taskEvidence(task)
                    + "\n"
                    + String.join(
                        "\n", task.hardConstraints() == null ? List.of() : task.hardConstraints()))
        .map(value -> value.toLowerCase(Locale.ROOT))
        .anyMatch(
            evidence ->
                containsAny(
                    evidence,
                    "real-http",
                    "real http",
                    "production server",
                    "server launched as a subprocess",
                    "loopback http url"));
  }

  static boolean installsDependenciesFromBuildOrTest(List<PlannedTask> tasks) {
    if (tasks == null) return false;
    return tasks.stream()
        .filter(Objects::nonNull)
        .map(
            task ->
                taskEvidence(task)
                    + "\n"
                    + String.join(
                        "\n", task.touchesFiles() == null ? List.of() : task.touchesFiles()))
        .anyMatch(
            evidence -> {
              String normalized = evidence.toLowerCase(Locale.ROOT);
              return containsAny(
                      normalized,
                      "make build",
                      "make test",
                      "build target",
                      "test target",
                      "`build`",
                      "build.sh",
                      "test.sh",
                      "run_tests.sh",
                      "run-tests.sh")
                  && containsAny(
                      normalized,
                      "pip install",
                      "npm install",
                      "npm ci",
                      "python -m venv",
                      "create venv",
                      "creates a venv");
            });
  }

  static OrderStatusContract findOrderStatusContract(List<PlannedTask> tasks, String requirements) {
    List<String> requiredValues = explicitOrderStatusValuesFromRequirements(requirements);
    if (requiredValues.size() >= 2) {
      String activeValue =
          List.of("DRAFT", "PENDING", "CREATED", "ACTIVE", "PROCESSING").stream()
              .filter(requiredValues::contains)
              .findFirst()
              .orElse(requiredValues.getFirst());
      return new OrderStatusContract(
          requiredValues,
          activeValue,
          "Order.status lifecycle values are exactly "
              + String.join(", ", requiredValues)
              + "; every producer, consumer, UI, and test must use those exact values.",
          null);
    }
    if (requirements.contains("create an order")
        && requirements.contains("reserve an order")
        && (requirements.contains("release/cancel a reservation")
            || requirements.contains("release reservation")
            || requirements.contains("cancel reservation"))) {
      return new OrderStatusContract(
          List.of("PENDING", "RESERVED"),
          "PENDING",
          "Order.status lifecycle values are exactly PENDING and RESERVED. New and released"
              + " orders are PENDING; successful reservation transitions PENDING→RESERVED; UI"
              + " and tests must use those exact values. Reservation release state belongs to"
              + " Reservation.status, not Order.status.",
          null);
    }
    if (tasks == null) return null;
    for (PlannedTask task : tasks) {
      if (task == null) continue;
      String evidence = taskEvidence(task);
      if (!isOrderStatusSchemaOwner(task, evidence) || !consumesOrderStatus(evidence)) continue;
      String rawDescription = safe(task.description());
      List<String> explicitValues = explicitOrderStatusValues(rawDescription);
      String raw =
          safe(task.title())
              + "\n"
              + rawDescription
              + "\n"
              + String.join(
                  "\n", task.hardConstraints() == null ? List.of() : task.hardConstraints());
      List<String> values =
          explicitValues.size() >= 2
              ? new ArrayList<>(explicitValues)
              : new ArrayList<>(
                  ORDER_STATES.stream()
                      .filter(value -> raw.matches("(?s).*\\b" + value + "\\b.*"))
                      .toList());
      if (values.size() == 1 && requirements.contains("cancel") && !values.contains("CANCELLED")) {
        values.add("CANCELLED");
      }
      if (values.size() < 2) continue;
      String activeValue =
          List.of("PENDING", "COMPLETED", "CONFIRMED", "CREATED", "ACTIVE", "PROCESSING").stream()
              .filter(values::contains)
              .findFirst()
              .orElse(values.getFirst());
      String constraint =
          values.equals(List.of("PENDING", "CANCELLED"))
              ? "Order.status lifecycle is exactly PENDING and CANCELLED. New orders use PENDING;"
                  + " successful cancellation transitions to CANCELLED; UI and tests offer"
                  + " cancellation only when status is not CANCELLED and expect CANCELLED"
                  + " afterward."
              : "Order.status lifecycle values are exactly "
                  + String.join(", ", values)
                  + "; every producer, consumer, UI, and test must use those exact values.";
      return new OrderStatusContract(List.copyOf(values), activeValue, constraint, task.id());
    }
    return null;
  }

  private static List<String> explicitOrderStatusValuesFromRequirements(String requirements) {
    java.util.regex.Matcher orderSentence =
        java.util.regex.Pattern.compile("(?is)\\borders?\\b[^.\\n]{0,320}(?:states?|status)")
            .matcher(safe(requirements));
    while (orderSentence.find()) {
      String block = orderSentence.group().toUpperCase(Locale.ROOT);
      List<String> values = new ArrayList<>();
      java.util.regex.Matcher token =
          java.util.regex.Pattern.compile("\\b[A-Z][A-Z_]+\\b").matcher(block);
      while (token.find()) {
        String value = token.group();
        if (ORDER_STATES.contains(value) && !values.contains(value)) values.add(value);
      }
      if (values.size() >= 2) return List.copyOf(values);
    }
    return List.of();
  }

  static boolean isOrderStatusSchemaOwner(PlannedTask task, String evidence) {
    String identity = taskIdentityEvidence(task);
    return containsAny(identity, "model", "migration", "alembic", "/entities/")
        && consumesOrderStatus(evidence);
  }

  static List<String> explicitOrderStatusValues(String description) {
    java.util.regex.Matcher statusBlock =
        java.util.regex.Pattern.compile(
                "(?is)(?:\\*\\*)?Order(?:\\*\\*)?.{0,500}?status.{0,100}?(?:enum|values?)?\\s*[:=(]?\\s*([^\\n"
                    + ".]{1,120})")
            .matcher(safe(description));
    if (!statusBlock.find()) return List.of();
    String block = statusBlock.group(1).toUpperCase(Locale.ROOT);
    List<String> values = new ArrayList<>();
    java.util.regex.Matcher token =
        java.util.regex.Pattern.compile("\\b[A-Z][A-Z_]+\\b").matcher(block);
    while (token.find()) {
      String value = token.group();
      if (ORDER_STATES.contains(value) && !values.contains(value)) values.add(value);
    }
    return List.copyOf(values);
  }
}
