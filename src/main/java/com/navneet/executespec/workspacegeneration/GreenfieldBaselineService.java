package com.navneet.executespec.workspacegeneration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;

import org.springframework.stereotype.Component;

/**
 * Plans and materializes the minimum server-owned baseline needed to make an empty BUILD workspace
 * mechanically verifiable.
 *
 * <p>{@link #plan} is side-effect-free and may run before a generation attempt is prepared so its
 * identity can be included in the frozen task contract. {@link #materialize} must run only after
 * the exact workspace-generation lease is active. It creates missing configuration files and never
 * overwrites customer/model content or writes product implementation.
 */
@Component
public class GreenfieldBaselineService {

  public GreenfieldBaselinePlan plan(
      Path workspaceRoot, PlannedTask task, WorkspaceOrigin workspaceOrigin) {
    return plan(workspaceRoot, task, null, workspaceOrigin);
  }

  public GreenfieldBaselinePlan plan(
      Path workspaceRoot, PlannedTask task, Plan plan, WorkspaceOrigin workspaceOrigin) {
    Path root = normalizedRoot(workspaceRoot);
    List<String> requiredPaths = requiredPaths(task);
    if (workspaceOrigin == null) throw new IllegalArgumentException("workspaceOrigin required");
    if (!workspaceOrigin.requiresBootstrap()) {
      GreenfieldBaselinePlan multiRepository = multiRepositoryPlan(root, task, requiredPaths);
      if (multiRepository != null) return multiRepository;
      return new GreenfieldBaselinePlan(
          GreenfieldBaselinePlan.Kind.EXISTING,
          "EXISTING_REPOSITORY",
          false,
          true,
          Map.of(),
          requiredPaths,
          initialFailures(root, requiredPaths, Map.of()),
          List.of());
    }

    String taskText = taskText(task);
    GreenfieldBaselinePlan.Kind kind = classify(requiredPaths, taskText);
    Map<String, String> files =
        baselineFiles(kind, requiredPaths, HttpEndpointContract.from(task, plan));
    // Only the external Python bootstrap is qualified. The older Node/Java/React baselines write
    // customer manifests/tests and then freeze those bytes, so the shared kernel must fail those
    // stacks before provider dispatch until their external adapters are proven.
    boolean supported =
        (kind == GreenfieldBaselinePlan.Kind.PYTHON
                || kind == GreenfieldBaselinePlan.Kind.NODE_JAVASCRIPT)
            && !requiredPaths.isEmpty();
    return new GreenfieldBaselinePlan(
        kind,
        runtimeSurface(kind, plan),
        true,
        supported,
        files,
        requiredPaths,
        initialFailures(root, requiredPaths, files),
        List.of());
  }

  private static GreenfieldBaselinePlan multiRepositoryPlan(
      Path root, PlannedTask task, List<String> requiredPaths) {
    if (requiredPaths.isEmpty()) return null;
    Map<String, List<String>> pathsByRoot = new java.util.TreeMap<>();
    for (String path : requiredPaths) {
      String[] segments = path.split("/", 3);
      if (segments.length < 3 || !"repos".equals(segments[0])) return null;
      String relativeRoot = "repos/" + segments[1];
      Path repository = root.resolve(relativeRoot).normalize();
      if (!repository.startsWith(root)
          || !Files.exists(repository.resolve(".git"))
          || !effectivelyEmpty(repository)) return null;
      pathsByRoot.computeIfAbsent(relativeRoot, ignored -> new ArrayList<>()).add(segments[2]);
    }
    Map<String, String> files = new LinkedHashMap<>();
    List<GreenfieldBaselinePlan.RepositoryTarget> targets = new ArrayList<>();
    boolean supported = true;
    for (Map.Entry<String, List<String>> entry : pathsByRoot.entrySet()) {
      List<String> relativePaths = entry.getValue().stream().distinct().sorted().toList();
      GreenfieldBaselinePlan.Kind kind = classify(relativePaths, taskText(task));
      if (kind != GreenfieldBaselinePlan.Kind.PYTHON
          && kind != GreenfieldBaselinePlan.Kind.NODE_JAVASCRIPT) supported = false;
      baselineFiles(kind, relativePaths, List.of())
          .forEach((path, content) -> files.put(entry.getKey() + "/" + path, content));
      targets.add(
          new GreenfieldBaselinePlan.RepositoryTarget(
              entry.getKey(), kind, runtimeSurface(kind), relativePaths));
    }
    return new GreenfieldBaselinePlan(
        GreenfieldBaselinePlan.Kind.MULTI_REPOSITORY,
        "MULTI_REPOSITORY",
        true,
        supported,
        files,
        requiredPaths,
        initialFailures(root, requiredPaths, files),
        targets);
  }

  /** Materialize a previously frozen plan without overwriting any existing path. */
  public List<String> materialize(Path workspaceRoot, GreenfieldBaselinePlan plan) {
    if (plan == null || !plan.greenfield() || !plan.supported()) return List.of();
    Path root = normalizedRoot(workspaceRoot);
    List<String> created = new ArrayList<>();
    for (Map.Entry<String, String> entry :
        plan.filesToCreate().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
      Path target = safeRelative(root, entry.getKey());
      if (Files.exists(target)) continue;
      try {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".executespec-baseline-", ".tmp");
        Files.writeString(temporary, entry.getValue(), StandardCharsets.UTF_8);
        try {
          Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unavailable) {
          Files.move(temporary, target);
        }
        created.add(root.relativize(target).toString().replace('\\', '/'));
      } catch (IOException failure) {
        throw new IllegalStateException(
            "GREENFIELD_BASELINE_WRITE_FAILED:" + entry.getKey(), failure);
      }
    }
    return List.copyOf(created);
  }

  private static GreenfieldBaselinePlan.Kind classify(List<String> paths, String taskText) {
    if (paths.stream().anyMatch(GreenfieldBaselineService::frontendPath)) {
      return GreenfieldBaselinePlan.Kind.FRONTEND_REACT;
    }
    if (paths.stream().anyMatch(path -> path.endsWith(".py"))) {
      return GreenfieldBaselinePlan.Kind.PYTHON;
    }
    if (paths.stream().anyMatch(path -> path.endsWith(".java"))) {
      return GreenfieldBaselinePlan.Kind.JAVA_MAVEN;
    }
    if (paths.stream().anyMatch(GreenfieldBaselineService::typescriptPath)) {
      return GreenfieldBaselinePlan.Kind.NODE_TYPESCRIPT;
    }
    if (paths.stream().anyMatch(GreenfieldBaselineService::javascriptPath)) {
      return GreenfieldBaselinePlan.Kind.NODE_JAVASCRIPT;
    }
    if (taskText.contains("react") || taskText.contains("frontend") || taskText.contains("vite")) {
      return GreenfieldBaselinePlan.Kind.FRONTEND_REACT;
    }
    if (taskText.contains("python") || taskText.contains("pytest")) {
      return GreenfieldBaselinePlan.Kind.PYTHON;
    }
    if (taskText.contains("java") || taskText.contains("maven")) {
      return GreenfieldBaselinePlan.Kind.JAVA_MAVEN;
    }
    if (taskText.contains("typescript") || taskText.contains("node")) {
      return GreenfieldBaselinePlan.Kind.NODE_TYPESCRIPT;
    }
    return GreenfieldBaselinePlan.Kind.UNSUPPORTED;
  }

  private static Map<String, String> baselineFiles(
      GreenfieldBaselinePlan.Kind kind,
      List<String> requiredPaths,
      List<String> endpointExpectations) {
    Map<String, String> files = new LinkedHashMap<>();
    switch (kind) {
      case PYTHON -> {
        files.put(".executespec/bootstrap/python.marker", "python3\n");
        files.put(
            ".executespec/bootstrap/test_acceptance.py",
            pythonAcceptanceTest(requiredPaths, endpointExpectations));
      }
      case NODE_TYPESCRIPT -> {
        // Unqualified adapter: coordinator fails before materialization/provider dispatch.
      }
      case NODE_JAVASCRIPT -> {
        String entry =
            requiredPaths.stream()
                .filter(GreenfieldBaselineService::javascriptPath)
                .findFirst()
                .orElseThrow();
        files.put(".executespec/bootstrap/node-js.marker", entry + "\n");
        files.put(".executespec/bootstrap/test_acceptance.mjs", nodeAcceptanceTest(requiredPaths));
      }
      case JAVA_MAVEN -> {
        // Unqualified adapter: coordinator fails before materialization/provider dispatch.
      }
      case FRONTEND_REACT -> {
        // Unqualified adapter: coordinator fails before materialization/provider dispatch.
      }
      case EXISTING, MULTI_REPOSITORY, UNSUPPORTED -> {
        // Existing repositories already own their baseline. Unsupported empty repositories fail
        // before mutation rather than receiving a guessed toolchain.
      }
    }
    return Map.copyOf(files);
  }

  private static String pythonAcceptanceTest(
      List<String> requiredPaths, List<String> endpointExpectations) {
    String assertions =
        requiredPaths.stream()
            .map(
                path ->
                    "    assert Path(\""
                        + path
                        + "\").exists(), \"required path missing: "
                        + path
                        + "\"")
            .collect(java.util.stream.Collectors.joining("\n"));
    String endpointAssertions =
        endpointExpectations.stream()
            .map(
                endpoint -> {
                  int split = endpoint.indexOf(' ');
                  String method = endpoint.substring(0, split);
                  String path = endpoint.substring(split + 1);
                  return "    assert (\""
                      + method
                      + "\", \""
                      + path
                      + "\") in routes, \"required route missing: "
                      + endpoint
                      + "\"";
                })
            .collect(java.util.stream.Collectors.joining("\n"));
    String entrypoint =
        requiredPaths.stream()
            .filter(path -> path.endsWith(".py"))
            .filter(path -> !path.toLowerCase(Locale.ROOT).contains("test"))
            .findFirst()
            .orElse("main.py");
    String routeTest =
        endpointExpectations.isEmpty()
            ? ""
            : "\n\ndef test_required_http_routes_are_wired():\n"
                + "    import ast\n"
                + "    source = Path(\""
                + entrypoint
                + "\").read_text(encoding=\"utf-8\")\n"
                + "    tree = ast.parse(source)\n"
                + "    routes = set()\n"
                + "    for node in ast.walk(tree):\n"
                + "        if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):\n"
                + "            continue\n"
                + "        for decorator in node.decorator_list:\n"
                + "            if not isinstance(decorator, ast.Call) or not decorator.args:\n"
                + "                continue\n"
                + "            function = decorator.func\n"
                + "            if not isinstance(function, ast.Attribute):\n"
                + "                continue\n"
                + "            path = decorator.args[0]\n"
                + "            if isinstance(path, ast.Constant) and isinstance(path.value, str):\n"
                + "                routes.add((function.attr.upper(), path.value))\n"
                + endpointAssertions
                + "\n";
    return "from pathlib import Path\n\n\ndef test_required_task_paths_exist():\n"
        + assertions
        + "\n"
        + routeTest;
  }

  private static String nodeAcceptanceTest(List<String> requiredPaths) {
    String assertions =
        requiredPaths.stream()
            .map(
                path ->
                    "  assert.ok(existsSync(\""
                        + path
                        + "\"), \"required path missing: "
                        + path
                        + "\");")
            .collect(java.util.stream.Collectors.joining("\n"));
    return "import test from \"node:test\";\n"
        + "import assert from \"node:assert/strict\";\n"
        + "import { existsSync } from \"node:fs\";\n\n"
        + "test(\"required task paths exist\", () => {\n"
        + assertions
        + "\n});\n";
  }

  private static List<String> requiredPaths(PlannedTask task) {
    if (task == null || task.requiredArtifacts() == null) return List.of();
    return task.requiredArtifacts().stream()
        .filter(path -> path != null && !path.isBlank())
        .map(path -> path.replace('\\', '/'))
        .filter(GreenfieldBaselineService::safeDeclaredPath)
        .distinct()
        .sorted()
        .toList();
  }

  private static List<String> initialFailures(
      Path root, List<String> requiredPaths, Map<String, String> plannedFiles) {
    List<String> failures = new ArrayList<>();
    for (String required : requiredPaths) {
      if (!Files.exists(root.resolve(required)) && !plannedFiles.containsKey(required)) {
        failures.add("REQUIRED_PATH_MISSING:" + required);
      }
    }
    failures.add("FINAL_ACCEPTANCE_NOT_YET_PROVEN");
    return List.copyOf(failures);
  }

  private static boolean effectivelyEmpty(Path root) {
    if (!Files.isDirectory(root)) return true;
    try (var children = Files.list(root)) {
      return children.allMatch(
          path ->
              Set.of(
                      ".git",
                      ".agent-context",
                      ".executespec",
                      ".DS_Store",
                      ".gitignore",
                      ".gitattributes")
                  .contains(path.getFileName() == null ? "" : path.getFileName().toString()));
    } catch (IOException failure) {
      throw new IllegalStateException("GREENFIELD_BASELINE_INSPECTION_FAILED", failure);
    }
  }

  private static Path normalizedRoot(Path workspaceRoot) {
    if (workspaceRoot == null) throw new IllegalArgumentException("workspaceRoot required");
    return workspaceRoot.toAbsolutePath().normalize();
  }

  private static Path safeRelative(Path root, String relative) {
    if (!safeDeclaredPath(relative)) {
      throw new IllegalArgumentException("GREENFIELD_BASELINE_PATH_INVALID:" + relative);
    }
    Path target = root.resolve(relative).normalize();
    if (!target.startsWith(root)) {
      throw new IllegalArgumentException("GREENFIELD_BASELINE_PATH_ESCAPES_WORKSPACE:" + relative);
    }
    return target;
  }

  private static boolean safeDeclaredPath(String path) {
    return path != null
        && !path.isBlank()
        && !path.startsWith("/")
        && !path.contains("..")
        && !path.contains("*")
        && path.matches("[A-Za-z0-9._/@+-]+(?:/[A-Za-z0-9._@+-]+)*");
  }

  private static String taskText(PlannedTask task) {
    if (task == null) return "";
    return ((task.title() == null ? "" : task.title())
            + " "
            + (task.description() == null ? "" : task.description()))
        .toLowerCase(Locale.ROOT);
  }

  private static boolean frontendPath(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    return lower.endsWith(".tsx") || lower.endsWith(".jsx");
  }

  private static boolean typescriptPath(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    return lower.endsWith(".ts");
  }

  private static boolean javascriptPath(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    return lower.endsWith(".js") || lower.endsWith(".mjs");
  }

  private static String runtimeSurface(GreenfieldBaselinePlan.Kind kind) {
    return runtimeSurface(kind, null);
  }

  private static String runtimeSurface(GreenfieldBaselinePlan.Kind kind, Plan plan) {
    if (plan != null) {
      return switch (plan.runtimeSurface()) {
        case HTTP -> "HTTP_SERVICE";
        case CLI -> "CLI_APPLICATION";
        case LIBRARY -> runtimeSurface(kind);
        case STATIC_WEB -> "STATIC_WEB";
        case MOBILE_APP -> "MOBILE_APP";
      };
    }
    return switch (kind) {
      case EXISTING -> "EXISTING_REPOSITORY";
      case PYTHON -> "PYTHON_LIBRARY";
      case NODE_JAVASCRIPT -> "NODE_JAVASCRIPT_APPLICATION";
      case NODE_TYPESCRIPT -> "NODE_TYPESCRIPT_LIBRARY";
      case JAVA_MAVEN -> "JAVA_MAVEN_MODULE";
      case FRONTEND_REACT -> "REACT_FRONTEND";
      case MULTI_REPOSITORY -> "MULTI_REPOSITORY";
      case UNSUPPORTED -> "UNRESOLVED";
    };
  }
}
