package com.navneet.executespec.workspacegeneration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.smoketest.BuildCommand;
import com.navneet.executespec.smoketest.BuildCommandResolver;
import com.navneet.executespec.smoketest.TestCommandResolver;
import com.navneet.executespec.smoketest.TestCommandResolver.TestCommand;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Produces canonical, model-independent authority and per-task acceptance policy digests. */
@Component
public class TaskAcceptanceContractResolver {

  private final ObjectMapper objectMapper;
  private final BuildCommandResolver buildCommands;
  private final TestCommandResolver testCommands;

  /** Compatibility constructor retained for focused schema-v1 tests. */
  public TaskAcceptanceContractResolver(ObjectMapper objectMapper) {
    this(objectMapper, new BuildCommandResolver(), new TestCommandResolver());
  }

  @Autowired
  public TaskAcceptanceContractResolver(
      ObjectMapper objectMapper,
      BuildCommandResolver buildCommands,
      TestCommandResolver testCommands) {
    this.objectMapper = objectMapper;
    this.buildCommands = buildCommands;
    this.testCommands = testCommands;
  }

  public TaskAcceptanceContract resolve(PlannedTask task, int taskIndex) {
    String testRequirement = testsRequired(task) ? "REQUIRED" : "OPTIONAL";
    List<String> touchPaths = task.touchesFiles() == null ? List.of() : task.touchesFiles();
    Map<String, Object> unsigned =
        Map.of(
            "schemaVersion",
            1,
            "taskId",
            task.id(),
            "taskIndex",
            taskIndex,
            "verificationPolicy",
            "SERVER_RESOLVED_V1",
            "buildRequirement",
            "REQUIRED_NON_NOOP",
            "testRequirement",
            testRequirement,
            "declaredTouchPaths",
            touchPaths.stream().sorted().toList());
    String digest = sha256(canonical(unsigned));
    return new TaskAcceptanceContract(
        1,
        task.id(),
        taskIndex,
        "SERVER_RESOLVED_V1",
        "REQUIRED_NON_NOOP",
        testRequirement,
        touchPaths.stream().sorted().toList(),
        digest);
  }

  /**
   * Freeze the complete failure-first command/check contract before any model or baseline mutation.
   */
  public TaskAcceptanceContract resolveFailureFirst(
      PlannedTask task, int taskIndex, Path workspaceRoot, GreenfieldBaselinePlan baseline) {
    return resolveFailureFirst(task, null, taskIndex, workspaceRoot, baseline);
  }

  public TaskAcceptanceContract resolveFailureFirst(
      PlannedTask task,
      Plan plan,
      int taskIndex,
      Path workspaceRoot,
      GreenfieldBaselinePlan baseline) {
    if (workspaceRoot == null) throw new IllegalArgumentException("workspaceRoot required");
    if (baseline == null) throw new IllegalArgumentException("baseline required");
    if (!baseline.supported()) {
      throw new IllegalStateException("GREENFIELD_RUNTIME_UNSUPPORTED:" + baseline.kind().name());
    }
    String testRequirement = testsRequired(task) ? "REQUIRED" : "OPTIONAL";
    List<String> touchPaths =
        task.touchesFiles() == null ? List.of() : task.touchesFiles().stream().sorted().toList();
    TaskAcceptanceContract.ResolvedCommand build = resolvedBuild(workspaceRoot, baseline);
    TaskAcceptanceContract.ResolvedCommand test =
        resolvedTest(workspaceRoot, baseline).orElse(null);
    List<TaskAcceptanceContract.AcceptanceCheck> prefix = new ArrayList<>();
    prefix.add(
        new TaskAcceptanceContract.AcceptanceCheck("BUILD_COMMAND_AVAILABLE", "workspace", false));
    if ("REQUIRED".equals(testRequirement)) {
      prefix.add(
          new TaskAcceptanceContract.AcceptanceCheck("TEST_COMMAND_AVAILABLE", "workspace", false));
    }
    List<String> endpoints = HttpEndpointContract.from(task, plan);
    List<TaskAcceptanceContract.AcceptanceCheck> finalChecks = new ArrayList<>();
    finalChecks.add(
        new TaskAcceptanceContract.AcceptanceCheck("REPOSITORY_SCOPE", "candidate", false));
    finalChecks.add(
        new TaskAcceptanceContract.AcceptanceCheck("REQUIRED_PATHS_PRESENT", "candidate", false));
    finalChecks.add(
        new TaskAcceptanceContract.AcceptanceCheck(
            "BOOTSTRAP_ARTIFACT_INTEGRITY", ".executespec/bootstrap", true));
    finalChecks.add(
        new TaskAcceptanceContract.AcceptanceCheck("MEANINGFUL_DIFF", "candidate", false));
    finalChecks.add(new TaskAcceptanceContract.AcceptanceCheck("BUILD_PASSED", "candidate", false));
    finalChecks.add(
        new TaskAcceptanceContract.AcceptanceCheck(
            "TESTS_PASSED", "candidate", "OPTIONAL".equals(testRequirement)));
    Map<String, Object> unsigned = new java.util.LinkedHashMap<>();
    unsigned.put("schemaVersion", 2);
    unsigned.put("taskId", task.id());
    unsigned.put("taskIndex", taskIndex);
    unsigned.put("verificationPolicy", "SERVER_RESOLVED_FAILURE_FIRST_V2");
    unsigned.put("buildRequirement", "REQUIRED_NON_NOOP");
    unsigned.put("testRequirement", testRequirement);
    unsigned.put("declaredTouchPaths", touchPaths);
    unsigned.put("runtimeSurface", baseline.runtimeSurface());
    AcceptanceProofStrength requiredProofStrength =
        AcceptanceProofStrength.requiredFor(baseline.runtimeSurface(), testRequirement);
    unsigned.put("requiredProofStrength", requiredProofStrength);
    unsigned.put("baselineKind", baseline.kind().name());
    unsigned.put("buildCommand", build);
    unsigned.put("testCommand", test);
    unsigned.put("requiredPaths", baseline.requiredTaskPaths());
    unsigned.put("requiredTestSelectors", List.of());
    unsigned.put("endpointExpectations", endpoints);
    unsigned.put("meaningfulDiffRequired", true);
    List<String> repositoryScopes = repositoryScopes(baseline);
    unsigned.put("allowedRepositoryScopes", repositoryScopes);
    unsigned.put("prefixChecks", prefix);
    unsigned.put("finalChecks", finalChecks);
    Map<String, String> bootstrapDigests =
        baseline.filesToCreate().entrySet().stream()
            .collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> sha256(entry.getValue())));
    unsigned.put("immutableBootstrapArtifactDigests", bootstrapDigests);
    String digest = sha256(canonical(unsigned));
    return new TaskAcceptanceContract(
        2,
        task.id(),
        taskIndex,
        "SERVER_RESOLVED_FAILURE_FIRST_V2",
        "REQUIRED_NON_NOOP",
        testRequirement,
        touchPaths,
        baseline.runtimeSurface(),
        requiredProofStrength,
        baseline.kind().name(),
        build,
        test,
        baseline.requiredTaskPaths(),
        List.of(),
        endpoints,
        true,
        repositoryScopes,
        prefix,
        finalChecks,
        bootstrapDigests,
        digest);
  }

  /** Binds attempts to the exact plan, original goal, and workspace selection admitted to BUILD. */
  public String authorityDigest(Plan plan, String originalGoal, String workspaceSummary) {
    return sha256(
        canonical(
            Map.of(
                "schemaVersion",
                1,
                "plan",
                plan,
                "originalGoal",
                originalGoal == null ? "" : originalGoal,
                "workspaceSummary",
                workspaceSummary == null ? "" : workspaceSummary)));
  }

  public String canonicalContract(TaskAcceptanceContract contract) {
    if (contract == null) throw new IllegalArgumentException("contract required");
    return canonical(contract);
  }

  public String canonicalInitialFailureEvidence(GreenfieldBaselinePlan baseline) {
    return baseline == null ? null : canonical(baseline.initialFailureEvidence());
  }

  private boolean testsRequired(PlannedTask task) {
    List<String> paths = task.touchesFiles() == null ? List.of() : task.touchesFiles();
    if (paths.stream().anyMatch(TaskAcceptanceContractResolver::isCodePath)) return true;
    String text =
        ((task.title() == null ? "" : task.title())
                + " "
                + (task.description() == null ? "" : task.description()))
            .toLowerCase(Locale.ROOT);
    boolean staticOnly =
        text.contains("documentation")
            || text.contains("readme")
            || text.contains("static asset")
            || text.contains("copy change")
            || text.contains("configuration only")
            || text.contains("config-only");
    return !staticOnly;
  }

  static boolean isCodePath(String path) {
    if (path == null) return false;
    String normalized = path.toLowerCase(Locale.ROOT);
    return normalized.matches(
        ".*\\.(java|kt|kts|scala|groovy|js|jsx|ts|tsx|py|go|rs|rb|php|cs|cpp|cc|c|h|hpp|swift|ex|exs)$");
  }

  private TaskAcceptanceContract.ResolvedCommand resolvedBuild(
      Path workspaceRoot, GreenfieldBaselinePlan baseline) {
    if (baseline.greenfield()) {
      if (baseline.kind() == GreenfieldBaselinePlan.Kind.MULTI_REPOSITORY) {
        return multiRepositoryBuild(baseline);
      }
      return switch (baseline.kind()) {
        case PYTHON -> command("python3 -m compileall -q .", 120, "inferred:python", "PYTHON");
        case NODE_JAVASCRIPT ->
            command(
                "node --check " + nodeJavascriptEntry(baseline),
                120,
                "executespec-bootstrap-node-check",
                "NODE");
        case NODE_TYPESCRIPT, JAVA_MAVEN, FRONTEND_REACT, UNSUPPORTED ->
            throw new IllegalStateException("unqualified greenfield adapter: " + baseline.kind());
        case MULTI_REPOSITORY -> throw new IllegalStateException("multi repository handled above");
        case EXISTING -> throw new IllegalStateException("greenfield EXISTING baseline is invalid");
      };
    }
    BuildCommand resolved = buildCommands.resolve(workspaceRoot, null);
    return command(
        resolved.command(), resolved.timeoutSeconds(), resolved.sourceDescription(), "INFERRED");
  }

  private Optional<TaskAcceptanceContract.ResolvedCommand> resolvedTest(
      Path workspaceRoot, GreenfieldBaselinePlan baseline) {
    if (baseline.greenfield()) {
      if (baseline.kind() == GreenfieldBaselinePlan.Kind.MULTI_REPOSITORY) {
        return multiRepositoryTest(baseline);
      }
      return switch (baseline.kind()) {
        case PYTHON ->
            Optional.of(
                command(
                    "python3 -m pytest -q .executespec/bootstrap/test_acceptance.py",
                    600,
                    "executespec-bootstrap-pytest",
                    "PYTEST"));
        case NODE_JAVASCRIPT ->
            Optional.of(
                command(
                    "node --test .executespec/bootstrap/test_acceptance.mjs",
                    600,
                    "executespec-bootstrap-node-test",
                    "NODE_TEST"));
        case NODE_TYPESCRIPT, JAVA_MAVEN, FRONTEND_REACT, UNSUPPORTED ->
            throw new IllegalStateException("unqualified greenfield adapter: " + baseline.kind());
        case EXISTING, MULTI_REPOSITORY -> Optional.empty();
      };
    }
    Optional<TestCommand> resolved = testCommands.resolve(workspaceRoot);
    return resolved.map(
        value ->
            command(
                value.command(), value.timeoutSeconds(), value.source(), value.framework().name()));
  }

  private static TaskAcceptanceContract.ResolvedCommand command(
      String command, int timeoutSeconds, String source, String framework) {
    return new TaskAcceptanceContract.ResolvedCommand(command, timeoutSeconds, source, framework);
  }

  private static String nodeJavascriptEntry(GreenfieldBaselinePlan baseline) {
    return baseline.requiredTaskPaths().stream()
        .filter(path -> path.matches("[A-Za-z0-9._@+-]+(?:/[A-Za-z0-9._@+-]+)*\\.(?:js|mjs)"))
        .findFirst()
        .orElse(".executespec/bootstrap/invalid-node-entry.js");
  }

  private static TaskAcceptanceContract.ResolvedCommand multiRepositoryBuild(
      GreenfieldBaselinePlan baseline) {
    List<TaskAcceptanceContract.ResolvedCommand> commands =
        baseline.repositoryTargets().stream()
            .map(TaskAcceptanceContractResolver::buildForTarget)
            .toList();
    String joined =
        java.util.stream.IntStream.range(0, commands.size())
            .mapToObj(
                index ->
                    "(cd "
                        + baseline.repositoryTargets().get(index).relativeRoot()
                        + " && "
                        + commands.get(index).command()
                        + ")")
            .collect(java.util.stream.Collectors.joining(" && "));
    int timeout =
        commands.stream().mapToInt(TaskAcceptanceContract.ResolvedCommand::timeoutSeconds).sum();
    String source =
        commands.size() == 1
            ? commands.getFirst().source()
                + "@"
                + baseline.repositoryTargets().getFirst().relativeRoot()
            : "inferred:changed-multi-root";
    return command(joined, Math.min(timeout, 1200), source, "MULTI");
  }

  private static Optional<TaskAcceptanceContract.ResolvedCommand> multiRepositoryTest(
      GreenfieldBaselinePlan baseline) {
    List<TaskAcceptanceContract.ResolvedCommand> commands =
        baseline.repositoryTargets().stream()
            .map(target -> testForKind(target.kind()))
            .flatMap(Optional::stream)
            .toList();
    if (commands.size() != baseline.repositoryTargets().size()) return Optional.empty();
    String joined =
        java.util.stream.IntStream.range(0, commands.size())
            .mapToObj(
                index ->
                    "(cd "
                        + baseline.repositoryTargets().get(index).relativeRoot()
                        + " && "
                        + commands.get(index).command()
                        + ")")
            .collect(java.util.stream.Collectors.joining(" && "));
    int timeout =
        commands.stream().mapToInt(TaskAcceptanceContract.ResolvedCommand::timeoutSeconds).sum();
    String source =
        commands.size() == 1
            ? commands.getFirst().source()
                + "@"
                + baseline.repositoryTargets().getFirst().relativeRoot()
            : "multi-root";
    String framework = commands.size() == 1 ? commands.getFirst().framework() : "MULTI";
    return Optional.of(command(joined, Math.min(timeout, 1200), source, framework));
  }

  private static TaskAcceptanceContract.ResolvedCommand buildForTarget(
      GreenfieldBaselinePlan.RepositoryTarget target) {
    return switch (target.kind()) {
      case PYTHON -> command("python3 -m compileall -q .", 120, "inferred:python", "PYTHON");
      case NODE_JAVASCRIPT ->
          command(
              "node --check "
                  + target.requiredPaths().stream()
                      .filter(
                          path ->
                              path.matches("[A-Za-z0-9._@+-]+(?:/[A-Za-z0-9._@+-]+)*\\.(?:js|mjs)"))
                      .findFirst()
                      .orElse(".executespec/bootstrap/invalid-node-entry.js"),
              120,
              "executespec-bootstrap-node-check",
              "NODE");
      case NODE_TYPESCRIPT, JAVA_MAVEN, FRONTEND_REACT, EXISTING, MULTI_REPOSITORY, UNSUPPORTED ->
          throw new IllegalStateException("unqualified repository target: " + target.kind());
    };
  }

  private static Optional<TaskAcceptanceContract.ResolvedCommand> testForKind(
      GreenfieldBaselinePlan.Kind kind) {
    return switch (kind) {
      case PYTHON ->
          Optional.of(
              command(
                  "python3 -m pytest -q .executespec/bootstrap/test_acceptance.py",
                  600,
                  "executespec-bootstrap-pytest",
                  "PYTEST"));
      case NODE_JAVASCRIPT ->
          Optional.of(
              command(
                  "node --test .executespec/bootstrap/test_acceptance.mjs",
                  600,
                  "executespec-bootstrap-node-test",
                  "NODE_TEST"));
      case NODE_TYPESCRIPT, JAVA_MAVEN, FRONTEND_REACT, EXISTING, MULTI_REPOSITORY, UNSUPPORTED ->
          Optional.empty();
    };
  }

  private static List<String> repositoryScopes(GreenfieldBaselinePlan baseline) {
    if (baseline.repositoryTargets().isEmpty()) return List.of(".");
    return baseline.repositoryTargets().stream()
        .map(GreenfieldBaselinePlan.RepositoryTarget::relativeRoot)
        .sorted()
        .toList();
  }

  private String canonical(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException impossible) {
      throw new IllegalStateException("TASK_ACCEPTANCE_CONTRACT_SERIALIZATION_FAILED", impossible);
    }
  }

  private static String sha256(String value) {
    return DigestUtils.sha256Hex(value.getBytes(StandardCharsets.UTF_8));
  }
}
