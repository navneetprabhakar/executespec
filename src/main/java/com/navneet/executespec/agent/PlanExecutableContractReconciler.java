package com.navneet.executespec.agent;

import static com.navneet.executespec.agent.PlanSafetyEvidenceSupport.isFrontendOnlyTask;
import static com.navneet.executespec.agent.TaskExpanderAgent.safe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministically closes mechanical runtime/test contracts that do not require provider judgment.
 */
final class PlanExecutableContractReconciler {
  private static final Pattern ROUTE_LITERAL = Pattern.compile("`(/[^`\\s]*)`");
  private static final Pattern ACTION_PATH =
      Pattern.compile(
          "(?i)\\b(?:POST|PUT|PATCH|DELETE)\\s+(/api/[a-z0-9_{}./:-]+/\\{[^}]+}/[a-z][a-z0-9_-]+)");
  private static final Pattern EXACT_PATH_BINDING =
      Pattern.compile("(?i)use these exact public api paths");
  private static final Pattern ASYNC_CLIENT_CHOICE =
      Pattern.compile(
          "(?i)(?:\\b(?:pick|choose|select|decide\\s+on)\\s+(?:one|either)\\s*(?::|-)?\\s*)?"
              + "(?:(?:httpx\\.)?AsyncClient\\s+(?:or|versus|vs\\.?)\\s+"
              + "(?:FastAPI(?:'s)?\\s+)?TestClient|(?:FastAPI(?:'s)?\\s+)?TestClient\\s+"
              + "(?:or|versus|vs\\.?)\\s+(?:httpx\\.)?AsyncClient)"
              + "(?:[^.\\n]{0,100}\\b(?:pick|choose|select)\\s+one\\b)?");
  private static final String READINESS_CONTRACT =
      "EXECUTABLE READINESS CONTRACT (binding): after launching uvicorn as a subprocess, poll a"
          + " health/readiness endpoint with a bounded 15-second startup timeout before issuing"
          + " test requests; fail with captured process logs if readiness is not reached.";
  private static final String NODE_E2E_CONTRACT =
      "EXECUTABLE PLAYWRIGHT CONTRACT (binding): use the @playwright/test dependency and a"
          + " package.json test:e2e script that runs `playwright test`. The Playwright webServer"
          + " process lifecycle starts both uvicorn and Vite, polls health/readiness with a bounded"
          + " timeout, uses a fresh isolated DATABASE_URL, and teardown stops both processes even"
          + " when a test fails.";
  private static final String GENERIC_NODE_E2E_CONTRACT =
      "EXECUTABLE PLAYWRIGHT CONTRACT (binding): use the @playwright/test dependency and a"
          + " package.json test:e2e script that runs `playwright test`. The Playwright webServer"
          + " lifecycle starts the project's documented backend and frontend commands, polls"
          + " health/readiness with a bounded timeout, uses fresh isolated test state, and"
          + " teardown stops both processes even when a test fails.";

  private PlanExecutableContractReconciler() {}

  record Result(Plan plan, int repairsApplied, List<String> summaries) {}

  static Result reconcile(Plan plan) {
    if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) {
      return new Result(plan, 0, List.of());
    }
    Plan scopeClosed = PlanScopeOwnershipReconciler.reconcile(plan);
    List<PlannedTask> tasks = new ArrayList<>(scopeClosed.tasks());
    List<String> summaries = new ArrayList<>();
    int applied = scopeClosed.equals(plan) ? 0 : 1;
    if (applied > 0) summaries.add("plan closes title/domain artifact ownership before execution");
    applied += resolveEstablishedAsyncTestClientChoices(tasks, summaries);
    applied += closeExactActionPathBindings(tasks, summaries);
    applied += removeStaleForeignFileAssignments(tasks, summaries);
    applied += closeRuntimeDriverDependencies(tasks, summaries);
    applied += closeExecutableTestContracts(tasks, summaries);
    applied += closeFrontendPlaceholderBindings(tasks, summaries);
    if (applied == 0) return new Result(plan, 0, List.of());
    return new Result(
        PlanSafetyStructureSupport.withTasks(
            scopeClosed, tasks, scopeClosed.buildCommand(), scopeClosed.projectType()),
        applied,
        List.copyOf(summaries));
  }

  private static int resolveEstablishedAsyncTestClientChoices(
      List<PlannedTask> tasks, List<String> summaries) {
    boolean asyncContract =
        tasks.stream()
            .filter(java.util.Objects::nonNull)
            .map(PlanExecutableContractReconciler::evidence)
            .map(value -> value.toLowerCase(Locale.ROOT))
            .anyMatch(
                value ->
                    value.contains("asyncsession")
                        || value.contains("create_async_engine")
                        || value.contains("sqlite+aiosqlite")
                        || value.contains("postgresql+asyncpg"));
    if (!asyncContract) return 0;
    int applied = 0;
    for (int index = 0; index < tasks.size(); index++) {
      PlannedTask task = tasks.get(index);
      if (task == null) continue;
      String description =
          ASYNC_CLIENT_CHOICE
              .matcher(safe(task.description()))
              .replaceAll("Use httpx.AsyncClient against the ASGI application");
      if (description.equals(task.description())) continue;
      tasks.set(
          index,
          PlanSafetyStructureSupport.withDescriptionConstraintsAndFiles(
              task, description, task.hardConstraints(), task.touchesFiles()));
      summaries.add(task.id() + " resolves test-client choice to the established async stack");
      applied++;
    }
    return applied;
  }

  private static int closeExactActionPathBindings(List<PlannedTask> tasks, List<String> summaries) {
    int applied = 0;
    for (int taskIndex = 0; taskIndex < tasks.size(); taskIndex++) {
      PlannedTask task = tasks.get(taskIndex);
      if (task == null || task.hardConstraints().isEmpty()) continue;
      LinkedHashSet<String> declaredActions = new LinkedHashSet<>();
      Matcher actionMatcher = ACTION_PATH.matcher(safe(task.description()));
      while (actionMatcher.find()) declaredActions.add(actionMatcher.group(1));
      if (declaredActions.isEmpty()) continue;
      List<String> constraints = new ArrayList<>(task.hardConstraints());
      boolean changed = false;
      for (int constraintIndex = 0; constraintIndex < constraints.size(); constraintIndex++) {
        String constraint = constraints.get(constraintIndex);
        if (!EXACT_PATH_BINDING.matcher(constraint).find()) continue;
        List<String> missing =
            declaredActions.stream()
                .filter(path -> !containsEquivalentPath(constraint, path))
                .toList();
        if (missing.isEmpty()) continue;
        constraints.set(
            constraintIndex,
            constraint.stripTrailing()
                + " Also bind every declared action path: "
                + String.join(", ", missing)
                + ".");
        summaries.add(task.id() + " completes exact action-path binding " + missing);
        changed = true;
      }
      if (!changed) continue;
      tasks.set(
          taskIndex,
          PlanSafetyStructureSupport.withDescriptionConstraintsAndFiles(
              task, task.description(), constraints, task.touchesFiles()));
      applied++;
    }
    return applied;
  }

  private static int removeStaleForeignFileAssignments(
      List<PlannedTask> tasks, List<String> summaries) {
    java.util.Map<String, FileOwner> ownerByBasename = new java.util.LinkedHashMap<>();
    Set<String> ambiguous = new LinkedHashSet<>();
    for (PlannedTask task : tasks) {
      if (task == null) continue;
      for (String raw : task.touchesFiles()) {
        String path = safe(raw).replace('\\', '/');
        String basename = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        FileOwner prior = ownerByBasename.putIfAbsent(basename, new FileOwner(task.id(), path));
        if (prior != null && !prior.taskId().equals(task.id())) ambiguous.add(basename);
      }
    }
    ambiguous.forEach(ownerByBasename::remove);

    int applied = 0;
    for (int index = 0; index < tasks.size(); index++) {
      PlannedTask task = tasks.get(index);
      if (task == null) continue;
      String description = task.description();
      List<String> reassigned = new ArrayList<>();
      for (FileOwner owner : ownerByBasename.values()) {
        if (owner.taskId().equals(task.id())
            || !description.toLowerCase(Locale.ROOT).contains(owner.basename())) continue;
        String sanitized = sanitizeForeignFileMutation(description, owner);
        if (sanitized.equals(description)) continue;
        description = sanitized;
        reassigned.add(owner.path() + " -> " + owner.taskId());
      }
      if (reassigned.isEmpty()) continue;
      tasks.set(
          index,
          PlanSafetyStructureSupport.withDescriptionConstraintsAndFiles(
              task, description, task.hardConstraints(), task.touchesFiles()));
      summaries.add(task.id() + " removes stale foreign-file assignments " + reassigned);
      applied++;
    }
    return applied;
  }

  private static int closeRuntimeDriverDependencies(
      List<PlannedTask> tasks, List<String> summaries) {
    int ownerIndex = dependencyOwnerIndex(tasks);
    if (ownerIndex < 0) return 0;
    String allEvidence =
        tasks.stream()
            .filter(java.util.Objects::nonNull)
            .map(PlanExecutableContractReconciler::evidence)
            .collect(java.util.stream.Collectors.joining("\n"))
            .toLowerCase(Locale.ROOT);
    PlannedTask owner = tasks.get(ownerIndex);
    String declaredDependencies =
        java.util.Arrays.stream(evidence(owner).toLowerCase(Locale.ROOT).split("[.\\n;]+"))
            .filter(
                clause ->
                    clause.contains("dependenc")
                        || clause.contains("requirements.txt")
                        || clause.contains(" install "))
            .collect(java.util.stream.Collectors.joining("\n"));
    List<String> missing = new ArrayList<>();
    if (allEvidence.contains("sqlite+aiosqlite") && !declaredDependencies.contains("aiosqlite")) {
      missing.add("aiosqlite");
    }
    if (allEvidence.contains("postgresql+asyncpg") && !declaredDependencies.contains("asyncpg")) {
      missing.add("asyncpg");
    }
    if ((allEvidence.contains("pytest-asyncio") || allEvidence.contains("async fixture"))
        && !declaredDependencies.contains("pytest-asyncio")) {
      missing.add("pytest-asyncio");
    }
    if (missing.isEmpty()) return 0;
    String contract =
        "EXECUTABLE DEPENDENCY CONTRACT (binding): the dependency manifest must declare "
            + String.join(", ", missing)
            + " because the selected runtime and test configuration import those packages.";
    tasks.set(ownerIndex, appendDescription(owner, contract));
    summaries.add(owner.id() + " declares required runtime/test dependencies " + missing);
    return 1;
  }

  private static int closeExecutableTestContracts(List<PlannedTask> tasks, List<String> summaries) {
    int applied = 0;
    Plan stackPlan = Plan.builder().tasks(tasks).build();
    boolean fastApiVite = PlanSafetyE2eSupport.usesFastApiViteStack(stackPlan);
    for (int index = 0; index < tasks.size(); index++) {
      PlannedTask task = tasks.get(index);
      if (task == null) continue;
      String evidence = evidence(task).toLowerCase(Locale.ROOT);
      boolean launchesUvicorn =
          evidence.contains("subprocess")
              && evidence.matches("(?s).*\\b(?:start|launch)\\w*\\b.{0,80}\\buvicorn\\b.*");
      if (launchesUvicorn
          && !evidence.matches(
              "(?s).*\\b(?:readiness|ready|poll|wait until|startup timeout|health check)\\b.*")) {
        task = appendDescription(task, READINESS_CONTRACT);
        tasks.set(index, task);
        evidence = evidence(task).toLowerCase(Locale.ROOT);
        summaries.add(task.id() + " adds bounded real-server readiness");
        applied++;
      }

      if (!evidence.contains("playwright") || !isBrowserE2e(task, evidence)) continue;
      List<String> missing = missingE2eParts(task, evidence, fastApiVite);
      if (missing.isEmpty()) continue;
      String contract =
          isPythonRunner(task, evidence)
              ? pythonE2eContract(task, fastApiVite)
              : (fastApiVite ? NODE_E2E_CONTRACT : GENERIC_NODE_E2E_CONTRACT);
      tasks.set(index, appendDescription(task, contract));
      summaries.add(task.id() + " closes Playwright execution contract " + missing);
      applied++;
    }
    return applied;
  }

  private static int closeFrontendPlaceholderBindings(
      List<PlannedTask> tasks, List<String> summaries) {
    PlannedTask scaffold =
        tasks.stream()
            .filter(java.util.Objects::nonNull)
            .filter(PlanExecutableContractReconciler::declaresPlaceholderRoutes)
            .findFirst()
            .orElse(null);
    if (scaffold == null) return 0;
    String router = routerArtifact(scaffold);
    if (router == null) return 0;
    Set<String> routes = declaredRoutes(scaffold);
    int applied = 0;
    for (int index = 0; index < tasks.size(); index++) {
      PlannedTask task = tasks.get(index);
      if (task == null
          || task.id().equals(scaffold.id())
          || !isFrontendOnlyTask(task)
          || touches(task, router)) continue;
      List<String> bindings = pageBindings(task, routes);
      if (bindings.isEmpty()) continue;
      List<String> files = new ArrayList<>(task.touchesFiles());
      files.add(router);
      String contract =
          "ROUTE REPLACEMENT CONTRACT (binding): update "
              + router
              + " so final page components replace the scaffold placeholders for "
              + String.join(", ", bindings)
              + ".";
      tasks.set(
          index,
          PlanSafetyStructureSupport.withDescriptionConstraintsAndFiles(
              task, appendParagraph(task.description(), contract), task.hardConstraints(), files));
      summaries.add(task.id() + " replaces scaffold route bindings " + bindings);
      applied++;
    }
    return applied;
  }

  private static List<String> missingE2eParts(
      PlannedTask task, String evidence, boolean fastApiVite) {
    List<String> missing = new ArrayList<>();
    boolean python = isPythonRunner(task, evidence);
    boolean node =
        containsFile(task, "package.json")
            || containsFile(task, "playwright.config.ts")
            || evidence.contains("@playwright/test")
            || evidence.contains("npx playwright");
    if (python) {
      if (!evidence.contains("pytest-playwright")) missing.add("dependency");
      if (!evidence.matches("(?s).*\\b(?:python\\s+-m\\s+pytest|pytest\\s+[^.\\n]+).*"))
        missing.add("runner script");
      if (!(evidence.contains("subprocess") || evidence.contains("popen"))
          || !PlanSafetyE2eSupport.hasBackendFrontendLifecycle(evidence, fastApiVite))
        missing.add("backend/frontend process lifecycle");
    } else if (node) {
      if (!evidence.contains("@playwright/test")) missing.add("dependency");
      if (!evidence.contains("test:e2e") || !evidence.contains("playwright test"))
        missing.add("runner script");
      if (!evidence.contains("webserver")
          || !PlanSafetyE2eSupport.hasBackendFrontendLifecycle(evidence, fastApiVite))
        missing.add("backend/frontend process lifecycle");
    } else {
      missing.add("runner");
    }
    if (!evidence.matches("(?s).*\\b(?:readiness|ready|health|wait\\s+for)\\b.*"))
      missing.add("readiness probe");
    if (!PlanSafetyE2eSupport.hasIsolatedTestState(evidence, fastApiVite))
      missing.add(fastApiVite ? "isolated DATABASE_URL" : "isolated test state");
    if (!evidence.matches("(?s).*\\b(?:teardown|terminate|stop|kill|cleanup)\\b.*"))
      missing.add("teardown");
    return List.copyOf(missing);
  }

  private static boolean isPythonRunner(PlannedTask task, String evidence) {
    return PlanSafetyE2eSupport.isPythonPlaywrightRunner(task, evidence);
  }

  private static String pythonE2eContract(PlannedTask task, boolean fastApiVite) {
    String testPath =
        task.touchesFiles().stream()
            .map(value -> safe(value).replace('\\', '/'))
            .filter(path -> path.toLowerCase(Locale.ROOT).endsWith(".py"))
            .filter(
                path -> {
                  String normalizedPath = path.toLowerCase(Locale.ROOT);
                  return normalizedPath.startsWith("e2e/")
                      || normalizedPath.contains("/e2e/")
                      || normalizedPath.contains("playwright")
                      || normalizedPath.contains("browser");
                })
            .findFirst()
            .orElse("tests");
    String lifecycle =
        fastApiVite
            ? "both uvicorn and Vite, polls health/readiness with a bounded timeout, uses a fresh"
                + " isolated DATABASE_URL"
            : "the project's documented backend and frontend commands, polls health/readiness"
                + " with a bounded timeout, uses fresh isolated test state";
    return "EXECUTABLE PLAYWRIGHT CONTRACT (binding): use the pytest-playwright dependency and run"
        + " `python -m pytest "
        + testPath
        + "`. A subprocess fixture starts "
        + lifecycle
        + ", and teardown terminates both processes even when a test fails.";
  }

  private static boolean isBrowserE2e(PlannedTask task, String evidence) {
    return evidence.contains("end-to-end")
        || evidence.contains("browser journey")
        || task.touchesFiles().stream()
            .map(PlanExecutableContractReconciler::normalized)
            .anyMatch(path -> path.contains("/e2e/") || path.startsWith("e2e/"));
  }

  private static int dependencyOwnerIndex(List<PlannedTask> tasks) {
    for (int index = 0; index < tasks.size(); index++) {
      PlannedTask task = tasks.get(index);
      if (task != null
          && task.touchesFiles().stream()
              .map(PlanExecutableContractReconciler::normalized)
              .anyMatch(
                  path -> path.endsWith("pyproject.toml") || path.endsWith("requirements.txt"))) {
        return index;
      }
    }
    return -1;
  }

  private static boolean declaresPlaceholderRoutes(PlannedTask task) {
    String evidence = evidence(task).toLowerCase(Locale.ROOT);
    return isFrontendOnlyTask(task)
        && routerArtifact(task) != null
        && (evidence.contains("placeholder") || evidence.contains("route stub"))
        && !declaredRoutes(task).isEmpty();
  }

  private static String routerArtifact(PlannedTask task) {
    return task.touchesFiles().stream()
        .map(value -> safe(value).replace('\\', '/'))
        .filter(
            path ->
                path.matches(
                    "(?i).*frontend/src/(?:App|routes?|router)(?:\\.[A-Za-z0-9_-]+)?\\.(?:tsx|jsx)$"))
        .findFirst()
        .orElse(null);
  }

  private static Set<String> declaredRoutes(PlannedTask task) {
    LinkedHashSet<String> routes = new LinkedHashSet<>();
    Matcher matcher = ROUTE_LITERAL.matcher(safe(task.description()));
    while (matcher.find()) routes.add(matcher.group(1));
    return Set.copyOf(routes);
  }

  private static List<String> pageBindings(PlannedTask task, Set<String> routes) {
    List<String> bindings = new ArrayList<>();
    for (String raw : task.touchesFiles()) {
      String path = normalized(raw);
      if (!path.matches(".*\\/pages\\/[^/]+\\.(?:tsx|jsx)$")) continue;
      String component =
          raw.substring(raw.replace('\\', '/').lastIndexOf('/') + 1)
              .replaceFirst("\\.(?i:tsx|jsx)$", "");
      String expected = expectedRoute(component, routes);
      if (expected != null) bindings.add(component + " -> " + expected);
    }
    return List.copyOf(bindings);
  }

  private static String expectedRoute(String component, Set<String> routes) {
    String words =
        component.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT).trim();
    String role = "";
    for (String suffix : List.of(" detail", " create", " new", " history")) {
      if (!words.endsWith(suffix)) continue;
      role = suffix.trim();
      words = words.substring(0, words.length() - suffix.length()).trim();
      break;
    }
    if (words.equals("dashboard") && routes.contains("/")) return "/";
    String resource = words;
    String collection =
        routes.stream()
            .filter(route -> !route.equals("/") && !route.contains(":") && !route.contains("{"))
            .filter(route -> singular(lastSegment(route)).equals(singular(resource)))
            .findFirst()
            .orElse(null);
    if (collection == null) return null;
    return switch (role) {
      case "detail" -> collection + "/:id";
      case "create", "new" -> collection + "/new";
      default -> collection;
    };
  }

  private static PlannedTask appendDescription(PlannedTask task, String contract) {
    if (safe(task.description()).contains(contract)) return task;
    return PlanSafetyStructureSupport.withDescriptionConstraintsAndFiles(
        task,
        appendParagraph(task.description(), contract),
        task.hardConstraints(),
        task.touchesFiles());
  }

  private static String appendParagraph(String description, String paragraph) {
    return safe(description).isBlank() ? paragraph : safe(description) + "\n\n" + paragraph;
  }

  private static boolean containsFile(PlannedTask task, String suffix) {
    return task.touchesFiles().stream()
        .map(PlanExecutableContractReconciler::normalized)
        .anyMatch(path -> path.endsWith(suffix));
  }

  private static boolean touches(PlannedTask task, String artifact) {
    return task.touchesFiles().stream()
        .map(value -> safe(value).replace('\\', '/'))
        .anyMatch(artifact::equals);
  }

  private static boolean containsEquivalentPath(String evidence, String path) {
    String normalizedEvidence =
        safe(evidence).replaceAll("\\{[^}]+}", "{}").toLowerCase(Locale.ROOT);
    String normalizedPath = safe(path).replaceAll("\\{[^}]+}", "{}").toLowerCase(Locale.ROOT);
    return normalizedEvidence.contains(normalizedPath);
  }

  private static String sanitizeForeignFileMutation(String description, FileOwner owner) {
    String[] lines = safe(description).split("\\R", -1);
    String basename = owner.basename();
    Pattern mutation =
        Pattern.compile(
            "(?i)\\b(?:update|modify|integrate|wrap|refine|handle|show|add|create|rewrite|replace)"
                + "\\b[^\\n;:]{0,240}\\b"
                + Pattern.quote(basename)
                + "\\b");
    boolean changed = false;
    for (int index = 0; index < lines.length; index++) {
      String line = lines[index];
      if (!line.toLowerCase(Locale.ROOT).contains(basename) || !mutation.matcher(line).find())
        continue;
      if (line.length() <= 800) {
        lines[index] =
            "SIBLING OWNERSHIP NOTE: use `"
                + owner.path()
                + "` only as integration context; "
                + owner.taskId()
                + " owns changes to that artifact.";
      } else {
        lines[index] =
            mutation
                .matcher(line)
                .replaceAll(
                    Matcher.quoteReplacement(
                        "use sibling-owned `" + owner.path() + "` as integration context"));
      }
      changed = true;
    }
    return changed ? String.join("\n", lines) : description;
  }

  private static String lastSegment(String route) {
    return route.substring(route.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
  }

  private static String singular(String value) {
    if (value.endsWith("ies") && value.length() > 3)
      return value.substring(0, value.length() - 3) + "y";
    if (value.endsWith("s") && value.length() > 3) return value.substring(0, value.length() - 1);
    return value;
  }

  private static String evidence(PlannedTask task) {
    return safe(task.title())
        + "\n"
        + safe(task.description())
        + "\n"
        + String.join("\n", task.hardConstraints());
  }

  private static String normalized(String value) {
    return safe(value).replace('\\', '/').toLowerCase(Locale.ROOT);
  }

  private record FileOwner(String taskId, String path) {
    String basename() {
      String normalized = path.replace('\\', '/');
      return normalized.substring(normalized.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    }
  }
}
