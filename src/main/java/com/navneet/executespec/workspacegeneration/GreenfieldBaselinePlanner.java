package com.navneet.executespec.workspacegeneration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;

/** Resolves the immutable server-owned baseline contract for a BUILD workspace. */
public class GreenfieldBaselinePlanner {

  public GreenfieldBaselinePlan plan(
      Path workspaceRoot, PlannedTask task, WorkspaceOrigin workspaceOrigin) {
    return plan(workspaceRoot, task, null, workspaceOrigin);
  }

  public GreenfieldBaselinePlan plan(
      Path workspaceRoot, PlannedTask task, Plan plan, WorkspaceOrigin workspaceOrigin) {
    Path root = normalizedRoot(workspaceRoot);
    List<String> requiredPaths = requiredPaths(task);
    if (workspaceOrigin == null) throw new IllegalArgumentException("workspaceOrigin required");
    // Origin is immutable run provenance. It does not mean that every later task must bootstrap
    // another project. Reuse the first task's immutable verifier without inventing new outputs.
    if (workspaceOrigin.requiresBootstrap()
        && plan != null
        && !plan.tasks().isEmpty()
        && !plan.tasks().getFirst().id().equals(task.id())) {
      GreenfieldBaselinePlan bootstrap = plan(root, plan.tasks().getFirst(), plan, workspaceOrigin);
      return new GreenfieldBaselinePlan(
          GreenfieldBaselinePlan.Kind.EXISTING,
          "EXISTING_REPOSITORY",
          false,
          bootstrap.supported(),
          bootstrap.filesToCreate(),
          requiredPaths,
          initialFailures(root, requiredPaths, Map.of()),
          List.of());
    }
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

    GreenfieldBaselinePlan.Kind kind = classify(requiredPaths, taskText(task));
    if (kind == GreenfieldBaselinePlan.Kind.UNSUPPORTED
        && plan != null
        && "python".equalsIgnoreCase(plan.projectType())
        && requiredPaths.stream()
            .map(path -> Path.of(path).getFileName().toString())
            .anyMatch(Set.of("requirements.txt", "pyproject.toml", "setup.cfg")::contains)) {
      // A manifest-only bootstrap need not repeat the language in its prose. The declared Python
      // project and its authoritative manifest identify the existing Python adapter together.
      kind = GreenfieldBaselinePlan.Kind.PYTHON;
    }
    Map<String, String> files =
        baselineFiles(kind, requiredPaths, HttpEndpointContract.from(task, plan));
    String runtimeSurface = runtimeSurface(kind, plan);
    boolean supported =
        !requiredPaths.isEmpty()
            && (kind == GreenfieldBaselinePlan.Kind.PYTHON
                || (kind == GreenfieldBaselinePlan.Kind.NODE_JAVASCRIPT
                    && !"HTTP_SERVICE".equals(runtimeSurface)));
    return new GreenfieldBaselinePlan(
        kind,
        runtimeSurface,
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
            GreenfieldAcceptanceTestSource.pythonAcceptanceTest(
                requiredPaths, endpointExpectations));
      }
      case NODE_JAVASCRIPT -> {
        String entry =
            requiredPaths.stream()
                .filter(GreenfieldBaselinePlanner::javascriptPath)
                .findFirst()
                .orElseThrow();
        files.put(".executespec/bootstrap/node-js.marker", entry + "\n");
        files.put(
            ".executespec/bootstrap/test_acceptance.mjs",
            GreenfieldAcceptanceTestSource.nodeAcceptanceTest(requiredPaths));
      }
      case NODE_TYPESCRIPT, JAVA_MAVEN, FRONTEND_REACT, EXISTING, MULTI_REPOSITORY, UNSUPPORTED -> {
        // These adapters are intentionally unqualified or already own their repository baseline.
      }
    }
    return Map.copyOf(files);
  }

  private static GreenfieldBaselinePlan.Kind classify(List<String> paths, String taskText) {
    if (paths.stream().anyMatch(GreenfieldBaselinePlanner::frontendPath)) {
      return GreenfieldBaselinePlan.Kind.FRONTEND_REACT;
    }
    if (paths.stream().anyMatch(path -> path.endsWith(".py"))) {
      return GreenfieldBaselinePlan.Kind.PYTHON;
    }
    if (paths.stream().anyMatch(path -> path.endsWith(".java"))) {
      return GreenfieldBaselinePlan.Kind.JAVA_MAVEN;
    }
    if (paths.stream().anyMatch(GreenfieldBaselinePlanner::typescriptPath)) {
      return GreenfieldBaselinePlan.Kind.NODE_TYPESCRIPT;
    }
    if (paths.stream().anyMatch(GreenfieldBaselinePlanner::javascriptPath)) {
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

  private static List<String> requiredPaths(PlannedTask task) {
    if (task == null || task.requiredArtifacts() == null) return List.of();
    return task.requiredArtifacts().stream()
        .filter(path -> path != null && !path.isBlank())
        .map(path -> path.replace('\\', '/'))
        .filter(GreenfieldBaselinePlanner::safeDeclaredPath)
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

  static boolean safeDeclaredPath(String path) {
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
    return path.toLowerCase(Locale.ROOT).endsWith(".ts");
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
