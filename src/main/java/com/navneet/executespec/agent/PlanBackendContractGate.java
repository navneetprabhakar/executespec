package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic cross-task checks for executable backend and test contracts. */
final class PlanBackendContractGate {
  private static final Pattern LIFECYCLE_FIELD =
      Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)\\.(status|state)\\b");
  private static final Pattern TYPED_FIELD =
      Pattern.compile(
          "(?i)\\b([a-z_][a-z0-9_]*)\\s*\\(\\s*(UUID|int(?:eger)?|str(?:ing)?|boolean|bool)\\b");
  private static final Pattern EXACT_ENUM =
      Pattern.compile(
          "(?i)\\b([a-z_][a-z0-9_ ]*)\\s+values\\s+are\\s+exactly\\s+([A-Z][A-Z_, /-]+)");
  private static final Pattern ENUM_ASSERTION =
      Pattern.compile("(?i)\\b([a-z_][a-z0-9_ ]*)\\s+strings?\\s*\\(([^)]*)\\)");
  private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Z][A-Z0-9_]*\\b");
  private static final Pattern ACTION_PATH =
      Pattern.compile(
          "(?i)\\b(?:POST|PUT|PATCH|DELETE)\\s+(/api/[a-z0-9_{}./:-]+/\\{[^}]+}/[a-z][a-z0-9_-]+)");
  private static final Pattern PATH = Pattern.compile("/api/[a-z0-9_{}./:-]+");

  private PlanBackendContractGate() {}

  static void addViolations(Plan plan, List<String> violations) {
    List<PlannedTask> tasks = plan.tasks();
    addLifecycleFieldViolations(tasks, violations);
    addPythonFilePackageCollisions(tasks, violations);
    addTypedFieldViolations(tasks, violations);
    addSplitFieldViolations(tasks, violations);
    addEnumAliasViolations(tasks, violations);
    addAsyncTestContractViolations(tasks, violations);
    addRuntimeDriverDependencyViolations(tasks, violations);
    addExactActionPathViolations(tasks, violations);
  }

  private static void addLifecycleFieldViolations(
      List<PlannedTask> tasks, List<String> violations) {
    Map<String, Map<String, Set<String>>> declarations = new LinkedHashMap<>();
    for (PlannedTask task : tasks) {
      if (task == null) continue;
      Matcher matcher = LIFECYCLE_FIELD.matcher(evidence(task));
      while (matcher.find()) {
        declarations
            .computeIfAbsent(
                matcher.group(1).toLowerCase(Locale.ROOT), ignored -> new LinkedHashMap<>())
            .computeIfAbsent(
                matcher.group(2).toLowerCase(Locale.ROOT), ignored -> new LinkedHashSet<>())
            .add(task.id());
      }
    }
    declarations.forEach(
        (entity, fields) -> {
          if (!fields.keySet().containsAll(Set.of("status", "state"))) return;
          violations.add(
              String.join(",", fields.get("state"))
                  + ": shared "
                  + entity
                  + " lifecycle alternates between persisted fields status and state");
        });
  }

  private static void addPythonFilePackageCollisions(
      List<PlannedTask> tasks, List<String> violations) {
    Map<String, Set<String>> owners = new LinkedHashMap<>();
    for (PlannedTask task : tasks) {
      if (task == null || task.touchesFiles() == null) continue;
      for (String file : task.touchesFiles()) {
        String path = normalized(file);
        owners.computeIfAbsent(path, ignored -> new LinkedHashSet<>()).add(task.id());
      }
    }
    for (Map.Entry<String, Set<String>> entry : owners.entrySet()) {
      String file = entry.getKey();
      if (!file.endsWith(".py")) continue;
      String packagePrefix = file.substring(0, file.length() - 3) + "/";
      Map.Entry<String, Set<String>> packageOwner =
          owners.entrySet().stream()
              .filter(candidate -> candidate.getKey().startsWith(packagePrefix))
              .findFirst()
              .orElse(null);
      if (packageOwner == null) continue;
      violations.add(
          String.join(",", entry.getValue())
              + ": Python module "
              + file
              + " collides with package "
              + packagePrefix
              + " owned by "
              + String.join(",", packageOwner.getValue()));
    }
  }

  private static void addTypedFieldViolations(List<PlannedTask> tasks, List<String> violations) {
    Map<String, List<TypedField>> fields = new LinkedHashMap<>();
    for (PlannedTask task : tasks) {
      if (task == null) continue;
      Matcher matcher = TYPED_FIELD.matcher(evidence(task));
      while (matcher.find()) {
        fields
            .computeIfAbsent(
                matcher.group(1).toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
            .add(new TypedField(task.id(), canonicalType(matcher.group(2))));
      }
    }
    fields.forEach(
        (field, declarations) -> {
          Set<String> types = new LinkedHashSet<>();
          declarations.forEach(value -> types.add(value.type()));
          if (types.size() < 2) return;
          violations.add(
              declarations.getLast().taskId()
                  + ": shared field "
                  + field
                  + " has conflicting declared types "
                  + types);
        });
  }

  private static void addSplitFieldViolations(List<PlannedTask> tasks, List<String> violations) {
    Map<String, Set<String>> identifiersByTask = new LinkedHashMap<>();
    Pattern snakeIdentifier = Pattern.compile("\\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\\b");
    Pattern structuredFields =
        Pattern.compile(
            "(?i)(?:\\b[a-z_][a-z0-9_]*\\s*\\([^)]*\\)|\\b(?:columns?|fields?)\\s*:[^\\n.]+)");
    for (PlannedTask task : tasks) {
      if (task == null) continue;
      LinkedHashSet<String> identifiers = new LinkedHashSet<>();
      Matcher structured = structuredFields.matcher(evidence(task).toLowerCase(Locale.ROOT));
      while (structured.find()) {
        Matcher identifier = snakeIdentifier.matcher(structured.group());
        while (identifier.find()) identifiers.add(identifier.group());
      }
      identifiersByTask.put(task.id(), identifiers);
    }
    identifiersByTask.forEach(
        (owner, fields) -> {
          for (String base : fields) {
            List<String> split =
                identifiersByTask.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals(owner))
                    .flatMap(entry -> entry.getValue().stream())
                    .filter(candidate -> candidate.startsWith(base + "_"))
                    .distinct()
                    .toList();
            if (split.size() < 2) continue;
            violations.add(
                owner
                    + ": shared field "
                    + base
                    + " conflicts with split fields "
                    + split
                    + " in another task");
          }
        });
  }

  private static void addEnumAliasViolations(List<PlannedTask> tasks, List<String> violations) {
    Map<String, Set<String>> canonical = new LinkedHashMap<>();
    for (PlannedTask task : tasks) {
      if (task == null) continue;
      Matcher matcher = EXACT_ENUM.matcher(evidence(task));
      while (matcher.find()) {
        canonical.put(lastLabelToken(matcher.group(1)), uppercaseIdentifiers(matcher.group(2)));
      }
    }
    if (canonical.isEmpty()) return;
    for (PlannedTask task : tasks) {
      if (task == null) continue;
      Matcher assertion = ENUM_ASSERTION.matcher(evidence(task));
      while (assertion.find()) {
        String label = lastLabelToken(assertion.group(1));
        Set<String> allowed =
            canonical.entrySet().stream()
                .filter(entry -> label.endsWith(entry.getKey()) || entry.getKey().endsWith(label))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        if (allowed == null) continue;
        Set<String> asserted = uppercaseIdentifiers(assertion.group(2));
        asserted.removeAll(allowed);
        if (!asserted.isEmpty()) {
          violations.add(
              task.id() + ": enum assertion uses aliases " + asserted + " outside " + allowed);
        }
      }
    }
  }

  private static void addAsyncTestContractViolations(
      List<PlannedTask> tasks, List<String> violations) {
    String allEvidence =
        tasks.stream()
            .filter(java.util.Objects::nonNull)
            .map(PlanBackendContractGate::evidence)
            .collect(java.util.stream.Collectors.joining("\n"))
            .toLowerCase(Locale.ROOT);
    boolean ownsPythonDependencies =
        tasks.stream()
            .filter(java.util.Objects::nonNull)
            .flatMap(
                task ->
                    task.touchesFiles() == null
                        ? java.util.stream.Stream.empty()
                        : task.touchesFiles().stream())
            .map(PlanBackendContractGate::normalized)
            .anyMatch(path -> path.endsWith("pyproject.toml") || path.endsWith("requirements.txt"));
    if (ownsPythonDependencies
        && (allEvidence.contains("pytest-asyncio") || allEvidence.contains("async fixture"))
        && !dependencyOwnerEvidence(tasks).contains("pytest-asyncio")) {
      violations.add(
          "plan: async pytest fixtures are required but dependency-owning tasks do not declare"
              + " pytest-asyncio");
    }
    for (PlannedTask task : tasks) {
      if (task == null) continue;
      String taskEvidence = evidence(task).toLowerCase(Locale.ROOT);
      boolean launchesServer =
          taskEvidence.contains("subprocess")
              && taskEvidence.matches("(?s).*\\b(?:start|launch)\\w*\\b.{0,80}\\buvicorn\\b.*");
      if (launchesServer
          && !taskEvidence.matches(
              "(?s).*\\b(?:readiness|ready|poll|wait until|startup timeout|health check)\\b.*")) {
        violations.add(
            task.id()
                + ": real-server functional fixture launches uvicorn without a bounded readiness"
                + " probe");
      }
    }
  }

  private static void addExactActionPathViolations(
      List<PlannedTask> tasks, List<String> violations) {
    for (PlannedTask task : tasks) {
      if (task == null || task.hardConstraints() == null) continue;
      String binding =
          task.hardConstraints().stream()
              .filter(
                  value ->
                      safe(value)
                          .toLowerCase(Locale.ROOT)
                          .contains("use these exact public api paths"))
              .collect(java.util.stream.Collectors.joining(" "));
      if (binding.isBlank()) continue;
      Set<String> boundPaths = paths(binding);
      Matcher action = ACTION_PATH.matcher(safe(task.description()));
      while (action.find()) {
        String declared = normalizePathVariables(action.group(1));
        if (boundPaths.contains(declared)) continue;
        violations.add(
            task.id()
                + ": exact public API path binding omits declared action path "
                + action.group(1));
      }
    }
  }

  private static void addRuntimeDriverDependencyViolations(
      List<PlannedTask> tasks, List<String> violations) {
    String allEvidence =
        tasks.stream()
            .filter(java.util.Objects::nonNull)
            .map(PlanBackendContractGate::evidence)
            .collect(java.util.stream.Collectors.joining("\n"))
            .toLowerCase(Locale.ROOT);
    String declaredDependencies = declaredDependencyEvidence(tasks);
    Map<String, String> driverByUrl =
        Map.of(
            "sqlite+aiosqlite", "aiosqlite",
            "postgresql+asyncpg", "asyncpg");
    driverByUrl.forEach(
        (urlMarker, driver) -> {
          if (allEvidence.contains(urlMarker) && !declaredDependencies.contains(driver)) {
            violations.add(
                "plan: runtime database URL "
                    + urlMarker
                    + " requires dependency-owning tasks to declare "
                    + driver);
          }
        });
  }

  private static String declaredDependencyEvidence(List<PlannedTask> tasks) {
    String ownerEvidence = dependencyOwnerEvidence(tasks);
    return java.util.Arrays.stream(ownerEvidence.split("[.\\n;]+"))
        .filter(
            clause ->
                clause.contains("dependenc")
                    || clause.contains("requirements.txt")
                    || clause.contains(" install "))
        .collect(java.util.stream.Collectors.joining("\n"));
  }

  private static String dependencyOwnerEvidence(List<PlannedTask> tasks) {
    return tasks.stream()
        .filter(java.util.Objects::nonNull)
        .filter(
            task ->
                task.touchesFiles() != null
                    && task.touchesFiles().stream()
                        .map(PlanBackendContractGate::normalized)
                        .anyMatch(
                            path ->
                                path.endsWith("pyproject.toml")
                                    || path.endsWith("requirements.txt")))
        .map(PlanBackendContractGate::evidence)
        .collect(java.util.stream.Collectors.joining("\n"))
        .toLowerCase(Locale.ROOT);
  }

  private static Set<String> paths(String value) {
    LinkedHashSet<String> paths = new LinkedHashSet<>();
    Matcher matcher = PATH.matcher(value);
    while (matcher.find()) paths.add(normalizePathVariables(matcher.group()));
    return Set.copyOf(paths);
  }

  private static Set<String> uppercaseIdentifiers(String value) {
    LinkedHashSet<String> identifiers = new LinkedHashSet<>();
    Matcher matcher = IDENTIFIER.matcher(value);
    while (matcher.find()) identifiers.add(matcher.group());
    return identifiers;
  }

  private static String normalizePathVariables(String path) {
    return path.toLowerCase(Locale.ROOT).replaceAll("\\{[^}]+}", "{id}").replaceAll("[.,;:]+$", "");
  }

  private static String lastLabelToken(String value) {
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    int space = normalized.lastIndexOf(' ');
    return space < 0 ? normalized : normalized.substring(space + 1);
  }

  private static String canonicalType(String value) {
    String type = value.toLowerCase(Locale.ROOT);
    if (type.startsWith("int")) return "int";
    if (type.startsWith("str")) return "string";
    if (type.equals("bool")) return "boolean";
    return type;
  }

  private static String normalized(String value) {
    return safe(value).replace('\\', '/').toLowerCase(Locale.ROOT);
  }

  private static String evidence(PlannedTask task) {
    return safe(task.title())
        + "\n"
        + safe(task.description())
        + "\n"
        + String.join("\n", task.hardConstraints() == null ? List.of() : task.hardConstraints());
  }

  private record TypedField(String taskId, String type) {}
}
