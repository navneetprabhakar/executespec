package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates copy-pasteable test commands and environment names promised by documentation tasks. */
final class PlanDocumentedCommandContractGate {
  private static final Pattern PYTEST_COMMAND =
      Pattern.compile("(?i)(?:\\bpython(?:3(?:\\.\\d+)?)?\\s+-m\\s+)?\\bpytest\\b([^\\r\\n`]*)");
  private static final Pattern PLAYWRIGHT_COMMAND =
      Pattern.compile("(?i)\\b(?:npx\\s+)?playwright\\s+test\\b([^\\r\\n`]*)");
  private static final Pattern ORIGINS_ENV = Pattern.compile("\\b([A-Z][A-Z0-9_]*_ORIGINS)\\b");
  private static final Pattern CD_PREFIX = Pattern.compile("(?i)\\bcd\\s+([^\\s;&|`]+)\\s*&&");
  private static final Pattern DOCUMENTATION_ENV_CONTEXT =
      Pattern.compile(
          "(?i)\\b(?:document|readme|setup|configuration|environment|env(?:ironment)?\\s+var|"
              + "export|set)\\b");
  private static final Pattern RUNTIME_ENV_CONTEXT =
      Pattern.compile(
          "(?i)\\b(?:read|load|parse|configure|configuration|setting|environment|"
              + "env(?:ironment)?\\s+var|allow[- ]?list)\\b");

  private PlanDocumentedCommandContractGate() {}

  static void addViolations(Plan plan, List<String> violations) {
    if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) return;
    Set<String> ownedArtifacts = ownedArtifacts(plan.tasks());
    List<PlannedTask> documentationTasks =
        plan.tasks().stream()
            .filter(java.util.Objects::nonNull)
            .filter(PlanDocumentedCommandContractGate::isDocumentationTask)
            .toList();
    for (PlannedTask task : documentationTasks) {
      addCommandViolations(task, ownedArtifacts, violations);
    }
    addOriginsEnvironmentViolations(plan.tasks(), documentationTasks, violations);
  }

  private static void addCommandViolations(
      PlannedTask task, Set<String> ownedArtifacts, List<String> violations) {
    String evidence = taskEvidence(task);
    addRunnerViolations(task, evidence, PYTEST_COMMAND, "pytest", ownedArtifacts, violations);
    addRunnerViolations(
        task, evidence, PLAYWRIGHT_COMMAND, "Playwright", ownedArtifacts, violations);
  }

  private static void addRunnerViolations(
      PlannedTask task,
      String evidence,
      Pattern commandPattern,
      String runner,
      Set<String> ownedArtifacts,
      List<String> violations) {
    Matcher command = commandPattern.matcher(evidence);
    while (command.find()) {
      if (isNegatedCommandMention(evidence, command.start())) continue;
      String workingDirectory = workingDirectory(evidence, command.start());
      for (String rawToken : command.group(1).trim().split("\\s+")) {
        String token = cleanToken(rawToken);
        if (token.isEmpty()) continue;
        if (isCommandBoundary(token)) break;
        if (token.startsWith("-")) continue;
        String target = stripNodeSelector(token);
        if (!isConcreteTestTarget(target)) continue;
        if (isMalformedPytestTarget(runner, target)) {
          violations.add(
              task.id()
                  + ": documented pytest target token "
                  + target
                  + " is malformed; keep pytest options separate from the test path");
          continue;
        }
        String resolved = resolveRepositoryPath(workingDirectory, target);
        if (resolved == null || ownsTarget(ownedArtifacts, resolved)) continue;
        violations.add(
            task.id()
                + ": documented "
                + runner
                + " target "
                + resolved
                + " is not owned by any plan task");
      }
    }
  }

  private static void addOriginsEnvironmentViolations(
      List<PlannedTask> tasks, List<PlannedTask> documentationTasks, List<String> violations) {
    Map<String, Set<String>> runtimeOwners = new LinkedHashMap<>();
    for (PlannedTask task : tasks) {
      if (task == null || !ownsRuntimeOrConfigArtifact(task)) continue;
      String evidence = taskEvidence(task);
      Matcher env = ORIGINS_ENV.matcher(evidence);
      while (env.find()) {
        String context = localContext(evidence, env.start(), env.end());
        if (RUNTIME_ENV_CONTEXT.matcher(context).find()
            && !isNegatedReference(context, env.group(1))) {
          runtimeOwners
              .computeIfAbsent(env.group(1), ignored -> new LinkedHashSet<>())
              .add(task.id());
        }
      }
    }
    // When runtime producers themselves disagree, another gate must resolve that ambiguity first;
    // documentation cannot be compared safely against an arbitrary winner.
    if (runtimeOwners.size() != 1) return;
    String runtimeVariable = runtimeOwners.keySet().iterator().next();
    String runtimeTaskIds = String.join(",", runtimeOwners.get(runtimeVariable));
    for (PlannedTask task : documentationTasks) {
      String evidence = taskEvidence(task);
      Matcher env = ORIGINS_ENV.matcher(evidence);
      while (env.find()) {
        String documentedVariable = env.group(1);
        if (documentedVariable.equals(runtimeVariable)) continue;
        String context = localContext(evidence, env.start(), env.end());
        if (!DOCUMENTATION_ENV_CONTEXT.matcher(context).find()
            || isNegatedReference(context, documentedVariable)) continue;
        violations.add(
            task.id()
                + ": documentation names "
                + documentedVariable
                + " but runtime/config task "
                + runtimeTaskIds
                + " establishes "
                + runtimeVariable);
      }
    }
  }

  private static Set<String> ownedArtifacts(List<PlannedTask> tasks) {
    LinkedHashSet<String> owned = new LinkedHashSet<>();
    for (PlannedTask task : tasks) {
      if (task == null || task.touchesFiles() == null) continue;
      for (String value : task.touchesFiles()) {
        String normalized = normalizePath(value);
        if (normalized != null) owned.add(normalized);
      }
    }
    return Set.copyOf(owned);
  }

  private static boolean isDocumentationTask(PlannedTask task) {
    String title = safe(task.title()).toLowerCase(Locale.ROOT);
    if (title.matches("(?s).*\\b(?:document(?:ation)?|readme|finaliz\\w*|setup guide)\\b.*")) {
      return true;
    }
    for (String value : task.touchesFiles() == null ? List.<String>of() : task.touchesFiles()) {
      String path = safe(value).replace('\\', '/').toLowerCase(Locale.ROOT);
      if (path.endsWith(".md")
          || path.endsWith(".rst")
          || path.endsWith(".adoc")
          || path.startsWith("docs/")
          || path.contains("/docs/")) return true;
    }
    return false;
  }

  private static boolean ownsRuntimeOrConfigArtifact(PlannedTask task) {
    for (String value : task.touchesFiles() == null ? List.<String>of() : task.touchesFiles()) {
      String path = safe(value).replace('\\', '/').toLowerCase(Locale.ROOT);
      if (path.isBlank()
          || path.contains("/test/")
          || path.contains("/tests/")
          || path.startsWith("test/")
          || path.startsWith("tests/")
          || path.endsWith(".md")
          || path.endsWith(".rst")
          || path.endsWith(".adoc")) continue;
      if (path.matches(".*\\.(?:py|java|kt|ts|tsx|js|jsx|go|rb|php|cs|ya?ml|properties|toml|env)$")
          || path.endsWith(".env.example")) return true;
    }
    return false;
  }

  private static String taskEvidence(PlannedTask task) {
    return safe(task.title())
        + "\n"
        + safe(task.description())
        + "\n"
        + String.join("\n", task.hardConstraints() == null ? List.of() : task.hardConstraints());
  }

  private static String workingDirectory(String evidence, int commandStart) {
    int boundary =
        Math.max(evidence.lastIndexOf('\n', commandStart), evidence.lastIndexOf('`', commandStart));
    String prefix = evidence.substring(Math.max(0, boundary + 1), commandStart);
    Matcher cd = CD_PREFIX.matcher(prefix);
    String directory = "";
    while (cd.find()) directory = cleanToken(cd.group(1));
    return directory;
  }

  private static String cleanToken(String token) {
    if (token == null) return "";
    return token.trim().replaceFirst("^[('\\\"`]+", "").replaceFirst("[),.;'\\\"`]+$", "");
  }

  private static String stripNodeSelector(String token) {
    int selector = token.indexOf("::");
    return selector < 0 ? token : token.substring(0, selector);
  }

  private static boolean isCommandBoundary(String token) {
    return token.equals("&&")
        || token.equals(";")
        || token.equals("|")
        || token.equalsIgnoreCase("then");
  }

  private static boolean isConcreteTestTarget(String token) {
    if (token == null || token.isBlank() || token.startsWith("/")) return false;
    if (token.matches(".*[$*?{}<>~].*")) return false;
    String path = token.replace('\\', '/').toLowerCase(Locale.ROOT);
    return path.matches("(?:^|.*/)(?:tests?|e2e|acceptance)(?:/.*)?")
        || path.matches(".*(?:^|/)[^/]*(?:test|spec)[^/]*\\.(?:py|ts|tsx|js|jsx)$");
  }

  private static boolean isMalformedPytestTarget(String runner, String target) {
    if (!"pytest".equalsIgnoreCase(runner)) return false;
    return target.replace('\\', '/').matches("(?i).*/--?[a-z][^/]*");
  }

  private static String resolveRepositoryPath(String workingDirectory, String target) {
    String base = normalizePath(workingDirectory);
    String candidate = normalizePath(target);
    if (candidate == null) return null;
    try {
      Path resolved =
          base == null || base.isBlank() ? Path.of(candidate) : Path.of(base).resolve(candidate);
      String normalized = resolved.normalize().toString().replace('\\', '/');
      if (normalized.equals("..") || normalized.startsWith("../")) return null;
      return normalized;
    } catch (RuntimeException invalidPath) {
      return null;
    }
  }

  private static String normalizePath(String value) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim().replace('\\', '/');
    while (normalized.startsWith("./")) normalized = normalized.substring(2);
    while (normalized.endsWith("/") && normalized.length() > 1) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    if (normalized.isBlank()
        || normalized.startsWith("/")
        || normalized.matches(".*[$*?{}<>~].*")) {
      return null;
    }
    return normalized;
  }

  private static boolean ownsTarget(Set<String> ownedArtifacts, String target) {
    if (ownedArtifacts.contains(target)) return true;
    String prefix = target.endsWith("/") ? target : target + "/";
    return ownedArtifacts.stream().anyMatch(path -> path.startsWith(prefix));
  }

  private static String localContext(String evidence, int start, int end) {
    return evidence.substring(Math.max(0, start - 140), Math.min(evidence.length(), end + 180));
  }

  private static boolean isNegatedReference(String context, String variable) {
    int variableAt = context.indexOf(variable);
    if (variableAt < 0) return false;
    String prefix = context.substring(Math.max(0, variableAt - 48), variableAt);
    return prefix.matches(
        "(?is).*(?:(?:do not|don't|never|must not)\\s+(?:use|set|document|export|read)"
            + "?|instead of)\\s+$");
  }

  private static boolean isNegatedCommandMention(String evidence, int commandStart) {
    String prefix = evidence.substring(Math.max(0, commandStart - 64), commandStart);
    return prefix.matches("(?is).*(?:do not|don't|never|must not)\\s+(?:use|run|invoke)\\s+$");
  }
}
