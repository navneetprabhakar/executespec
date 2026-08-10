package com.navneet.executespec.workspacegeneration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.smoketest.BuildCommand;
import com.navneet.executespec.smoketest.BuildCommandResolver;
import com.navneet.executespec.smoketest.BuildVerificationResult;
import com.navneet.executespec.smoketest.TestCommandResolver;
import com.navneet.executespec.smoketest.TestCommandResolver.TestCommand;
import com.navneet.executespec.smoketest.WorkspaceTestRunner;
import com.navneet.executespec.smoketest.WorkspaceTestRunner.DetailedResult;
import com.navneet.executespec.testgen.model.TestExecutionSummary;
import com.navneet.executespec.tool.BuildProjectTool;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.ToolResult;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Runs the frozen acceptance policy using only server-resolved repository commands. */
@Component
@RequiredArgsConstructor
public class TaskAcceptanceVerifier {

  private static final int MAX_OUTPUT_CHARS = 12_000;

  private final BuildCommandResolver buildCommands;
  private final TestCommandResolver testCommands;
  private final BuildProjectTool buildProjectTool;
  private final WorkspaceTestRunner testRunner;
  private final ObjectMapper objectMapper;

  /**
   * Produces the deterministic red signal that must exist before a schema-v2 worker can dispatch.
   * Missing required paths are executable file assertions. Existing-code tasks instead run the
   * frozen build/test surface; a green workspace is not silently relabelled as failure-first.
   */
  public InitialFailure probeInitial(ExecutionContext context, TaskAcceptanceContract contract) {
    BuildCommand build = buildCommands.resolve(context.workspaceRoot(), null);
    Optional<TestCommand> test = testCommands.resolve(context.workspaceRoot());
    if (!matches(contract.buildCommand(), build)
        || !matches(contract.testCommand(), test.orElse(null))) {
      return new InitialFailure(
          "UNAVAILABLE",
          "INITIAL_COMMAND_CONTRACT_DRIFT",
          "The server-resolved build/test commands no longer match the frozen contract",
          null,
          List.of());
    }
    if ("fallback:no-op".equals(build.sourceDescription())) {
      return new InitialFailure(
          "UNAVAILABLE",
          "INITIAL_BUILD_COMMAND_UNAVAILABLE",
          "No executable server-resolved build command is available",
          null,
          List.of());
    }
    if ("REQUIRED".equals(contract.testRequirement()) && test.isEmpty()) {
      return new InitialFailure(
          "UNAVAILABLE",
          "INITIAL_TEST_COMMAND_UNAVAILABLE",
          "No executable server-resolved test command is available",
          null,
          List.of());
    }
    if (!contract.endpointExpectations().isEmpty()
        && !endpointExpectationsPassed(
            context.workspaceRoot(),
            contract.allowedRepositoryScopes(),
            contract.endpointExpectations())) {
      return new InitialFailure(
          "FAILURE_OBSERVED",
          "ENDPOINT_CONTRACT_UNSATISFIED",
          "The admitted OpenAPI contract does not declare every required method/path",
          null,
          contract.endpointExpectations());
    }
    List<String> missing = missingRequiredPaths(context.workspaceRoot(), contract.requiredPaths());
    if (!missing.isEmpty()) {
      return new InitialFailure(
          "FAILURE_OBSERVED",
          "REQUIRED_PATH_MISSING",
          "Required path assertions are red: " + missing,
          null,
          missing);
    }
    com.navneet.executespec.integration.RunWorkspaceAuthorityService.assertCorrectionToolDispatch(
        context.runId());
    ToolResult buildResult = buildProjectTool.execute(objectMapper.createObjectNode(), context);
    String buildOutput =
        buildResult instanceof ToolResult.Ok ok
            ? ok.content()
            : "tool error: "
                + (buildResult instanceof ToolResult.Err err ? err.message() : "unknown");
    BuildVerificationResult parsed = BuildVerificationResult.parse(buildOutput);
    if (!parsed.passed()) {
      return new InitialFailure(
          parsed.status() == BuildVerificationResult.Status.UNAVAILABLE
              ? "UNAVAILABLE"
              : "FAILURE_OBSERVED",
          parsed.status() == BuildVerificationResult.Status.UNAVAILABLE
              ? "INITIAL_BUILD_UNAVAILABLE"
              : "INITIAL_BUILD_FAILED",
          bounded(buildOutput),
          build.command(),
          List.of());
    }
    if (test.isEmpty()) {
      return new InitialFailure(
          "UNAVAILABLE",
          "INITIAL_TEST_COMMAND_UNAVAILABLE",
          "No executable server-resolved test command is available",
          null,
          List.of());
    }
    com.navneet.executespec.integration.RunWorkspaceAuthorityService.assertCorrectionToolDispatch(
        context.runId());
    Optional<DetailedResult> tested =
        testRunner.runTestsDetailed(context.runId(), context.workspaceRoot());
    if (tested.isEmpty()) {
      return new InitialFailure(
          "UNAVAILABLE",
          "INITIAL_TEST_EVIDENCE_UNAVAILABLE",
          "The frozen test command produced no trustworthy deterministic evidence",
          test.get().command(),
          List.of());
    }
    DetailedResult detail = tested.get();
    TestExecutionSummary summary = detail.summary();
    boolean red = detail.exitCode() != 0 || summary.getFailed() > 0 || summary.getErrors() > 0;
    return new InitialFailure(
        red ? "FAILURE_OBSERVED" : "ALREADY_PASSED",
        red ? "INITIAL_TESTS_FAILED" : "NO_INITIAL_FAILURE",
        bounded(detail.outputTail()),
        detail.command(),
        List.of());
  }

  public WorkspaceGenerationLifecycle.Verification verify(
      ExecutionContext context, TaskAcceptanceContract contract, List<String> actualChangedFiles) {
    Instant started = Instant.now();
    BuildCommand build = buildCommands.resolve(context.workspaceRoot(), null);
    Optional<TestCommand> test = testCommands.resolve(context.workspaceRoot());
    List<String> changedFiles =
        actualChangedFiles == null ? List.of() : actualChangedFiles.stream().sorted().toList();
    boolean mutationPresent = !changedFiles.isEmpty();
    boolean actualCodeChanged =
        changedFiles.stream().anyMatch(TaskAcceptanceContractResolver::isCodePath);
    boolean testRequired = "REQUIRED".equals(contract.testRequirement()) || actualCodeChanged;
    boolean schemaV2 = contract.schemaVersion() >= 2;
    boolean requiredPathsPresent =
        requiredPathsPresent(context.workspaceRoot(), contract.requiredPaths());
    boolean frozenBuildMatches = !schemaV2 || matches(contract.buildCommand(), build);
    boolean frozenTestMatches = !schemaV2 || matches(contract.testCommand(), test.orElse(null));

    Map<String, Object> commandContract = new LinkedHashMap<>();
    commandContract.put("schemaVersion", contract.schemaVersion());
    commandContract.put("policy", contract.verificationPolicy());
    commandContract.put("buildSource", build.sourceDescription());
    commandContract.put("buildCommand", build.command());
    commandContract.put("buildTimeoutSeconds", build.timeoutSeconds());
    commandContract.put("testRequirement", contract.testRequirement());
    commandContract.put("effectiveTestRequirement", testRequired ? "REQUIRED" : "OPTIONAL");
    commandContract.put("actualChangedFiles", changedFiles);
    commandContract.put("runtimeSurface", contract.runtimeSurface());
    commandContract.put("baselineKind", contract.baselineKind());
    commandContract.put("frozenBuildCommand", contract.buildCommand());
    commandContract.put("frozenTestCommand", contract.testCommand());
    commandContract.put("requiredPaths", contract.requiredPaths());
    commandContract.put("requiredPathsPresent", requiredPathsPresent);
    commandContract.put("frozenBuildMatches", frozenBuildMatches);
    commandContract.put("frozenTestMatches", frozenTestMatches);
    commandContract.put("testSource", test.map(TestCommand::source).orElse(null));
    commandContract.put("testCommand", test.map(TestCommand::command).orElse(null));
    commandContract.put("testTimeoutSeconds", test.map(TestCommand::timeoutSeconds).orElse(null));

    ObjectNode noPlannerCommand = objectMapper.createObjectNode();
    com.navneet.executespec.integration.RunWorkspaceAuthorityService.assertCorrectionToolDispatch(
        context.runId());
    ToolResult buildResult = buildProjectTool.execute(noPlannerCommand, context);
    String buildOutput =
        buildResult instanceof ToolResult.Ok ok
            ? ok.content()
            : "tool error: "
                + (buildResult instanceof ToolResult.Err err ? err.message() : "unknown");
    BuildVerificationResult parsed = BuildVerificationResult.parse(buildOutput);
    boolean noOp = "fallback:no-op".equals(build.sourceDescription());
    boolean exactResolvedBuild =
        buildOutput.contains("command=" + build.command() + "\n")
            && buildOutput.contains("source=" + build.sourceDescription());
    boolean buildPassed =
        parsed.passed()
            && !noOp
            && exactResolvedBuild
            && frozenBuildMatches
            && requiredPathsPresent;
    String outcome;

    Optional<DetailedResult> testResult = Optional.empty();
    if (buildPassed && test.isPresent()) {
      com.navneet.executespec.integration.RunWorkspaceAuthorityService.assertCorrectionToolDispatch(
          context.runId());
      testResult = testRunner.runTestsDetailed(context.runId(), context.workspaceRoot());
    }
    TestExecutionSummary summary = testResult.map(DetailedResult::summary).orElse(null);
    boolean testsExecuted = testResult.isPresent();
    boolean testsPassed =
        summary != null
            && testResult.get().exitCode() == 0
            && summary.getTotalTests() > 0
            && summary.getFailed() == 0
            && summary.getErrors() == 0;
    boolean endpointExpectationsPassed =
        endpointExpectationsPassed(
            context.workspaceRoot(),
            contract.allowedRepositoryScopes(),
            contract.endpointExpectations());
    TaskAcceptanceCheckEvaluator.Evaluation checkEvaluation =
        TaskAcceptanceCheckEvaluator.evaluate(
            contract,
            changedFiles,
            !noOp && frozenBuildMatches,
            test.isPresent() && frozenTestMatches,
            requiredPathsPresent,
            buildPassed,
            testsExecuted,
            testsPassed,
            endpointExpectationsPassed);

    if (schemaV2 && checkEvaluation.status() == TaskAcceptanceCheckEvaluator.Status.FAILED) {
      outcome = "FAILED";
    } else if (schemaV2
        && checkEvaluation.status() == TaskAcceptanceCheckEvaluator.Status.UNAVAILABLE) {
      outcome = "UNAVAILABLE";
    } else if (!mutationPresent) {
      outcome = "FAILED";
    } else if (noOp || parsed.status() == BuildVerificationResult.Status.UNAVAILABLE) {
      outcome = "UNAVAILABLE";
    } else if (!buildPassed) {
      outcome = "FAILED";
    } else if (testRequired && test.isEmpty()) {
      outcome = "UNAVAILABLE";
    } else if (testRequired && !testsPassed) {
      outcome = testsExecuted ? "FAILED" : "UNAVAILABLE";
    } else if (test.isPresent() && testsExecuted && !testsPassed) {
      outcome = "FAILED";
    } else {
      outcome = "PASSED";
    }

    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("schemaVersion", contract.schemaVersion());
    evidence.put("taskContractDigest", contract.digest());
    evidence.put("buildStatus", parsed.status().name());
    evidence.put("exactResolvedBuild", exactResolvedBuild);
    evidence.put("frozenBuildMatches", frozenBuildMatches);
    evidence.put("frozenTestMatches", frozenTestMatches);
    evidence.put("requiredPathsPresent", requiredPathsPresent);
    evidence.put("actualChangedFiles", changedFiles);
    evidence.put("actualCodeChanged", actualCodeChanged);
    evidence.put("buildExitCode", parsed.exitCode());
    evidence.put("buildOutput", bounded(buildOutput));
    evidence.put("testsExecuted", testsExecuted);
    evidence.put("testExitCode", testResult.map(DetailedResult::exitCode).orElse(null));
    evidence.put(
        "testOutput",
        testResult
            .map(DetailedResult::outputTail)
            .map(TaskAcceptanceVerifier::bounded)
            .orElse(null));
    evidence.put("testsTotal", summary == null ? 0 : summary.getTotalTests());
    evidence.put("testsPassed", summary == null ? 0 : summary.getPassed());
    evidence.put("testsFailed", summary == null ? 0 : summary.getFailed());
    evidence.put("testsErrors", summary == null ? 0 : summary.getErrors());
    evidence.put("testsSkipped", summary == null ? 0 : summary.getSkipped());
    evidence.put("endpointExpectations", contract.endpointExpectations());
    evidence.put("endpointExpectationsPassed", endpointExpectationsPassed);
    evidence.put("acceptanceCheckOutcome", checkEvaluation.status().name());
    evidence.put("acceptanceCheckResults", checkEvaluation.results());

    return new WorkspaceGenerationLifecycle.Verification(
        testsExecuted ? "BUILD_AND_TEST" : "BUILD_ONLY",
        json(commandContract),
        outcome,
        buildPassed,
        testsExecuted,
        summary == null ? 0 : summary.getPassed(),
        summary == null ? 0 : summary.getFailed(),
        summary == null ? 0 : summary.getErrors(),
        summary == null ? 0 : summary.getSkipped(),
        json(evidence),
        started,
        Instant.now());
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException impossible) {
      throw new IllegalStateException("TASK_ACCEPTANCE_EVIDENCE_SERIALIZATION_FAILED", impossible);
    }
  }

  private static boolean matches(
      TaskAcceptanceContract.ResolvedCommand frozen, BuildCommand actual) {
    if (frozen == null || actual == null) return false;
    return java.util.Objects.equals(frozen.command(), actual.command())
        && frozen.timeoutSeconds() == actual.timeoutSeconds()
        && java.util.Objects.equals(frozen.source(), actual.sourceDescription());
  }

  private static boolean matches(
      TaskAcceptanceContract.ResolvedCommand frozen, TestCommand actual) {
    if (frozen == null && actual == null) return true;
    if (frozen == null || actual == null) return false;
    return java.util.Objects.equals(frozen.command(), actual.command())
        && frozen.timeoutSeconds() == actual.timeoutSeconds()
        && java.util.Objects.equals(frozen.source(), actual.source())
        && java.util.Objects.equals(frozen.framework(), actual.framework().name());
  }

  private static boolean requiredPathsPresent(Path workspaceRoot, List<String> requiredPaths) {
    return missingRequiredPaths(workspaceRoot, requiredPaths).isEmpty();
  }

  private static List<String> missingRequiredPaths(Path workspaceRoot, List<String> requiredPaths) {
    Path root = workspaceRoot.toAbsolutePath().normalize();
    java.util.ArrayList<String> missing = new java.util.ArrayList<>();
    for (String required : requiredPaths) {
      if (required == null || required.isBlank() || required.startsWith("/")) {
        missing.add(String.valueOf(required));
        continue;
      }
      Path resolved = root.resolve(required).normalize();
      if (!resolved.startsWith(root) || !Files.exists(resolved)) missing.add(required);
    }
    return List.copyOf(missing);
  }

  private static String bounded(String value) {
    if (value == null || value.length() <= MAX_OUTPUT_CHARS) return value;
    return value.substring(0, 8_000)
        + "\n...[truncated]...\n"
        + value.substring(value.length() - 4_000);
  }

  private boolean endpointExpectationsPassed(
      Path workspaceRoot, List<String> scopes, List<String> expectations) {
    if (expectations == null || expectations.isEmpty()) return true;
    Path workspace = workspaceRoot.toAbsolutePath().normalize();
    List<String> roots = scopes == null || scopes.isEmpty() ? List.of(".") : scopes;
    List<Path> candidates =
        roots.stream()
            .flatMap(
                root ->
                    java.util.stream.Stream.of("openapi.yaml", "openapi.yml", "openapi.json")
                        .map(name -> workspace.resolve(root).resolve(name).normalize()))
            .filter(path -> path.startsWith(workspace))
            .filter(Files::isRegularFile)
            .toList();
    for (Path candidate : candidates) {
      try {
        if (Files.size(candidate) > 1_000_000) continue;
        String body = Files.readString(candidate);
        Object parsed =
            candidate.toString().endsWith(".json")
                ? objectMapper.readValue(body, Object.class)
                : com.navneet.executespec.util.SafeYamlLoader.load(body);
        if (parsed instanceof Map<?, ?> root
            && root.get("paths") instanceof Map<?, ?> paths
            && expectations.stream()
                .allMatch(expectation -> endpointDeclared(paths, expectation))) {
          return true;
        }
      } catch (Exception ignoredMalformedContract) {
        // Another candidate may still be the admitted contract; malformed files never pass.
      }
    }
    return false;
  }

  private static boolean endpointDeclared(Map<?, ?> paths, String expectation) {
    if (expectation == null) return false;
    int separator = expectation.indexOf(' ');
    if (separator <= 0 || separator == expectation.length() - 1) return false;
    String method = expectation.substring(0, separator).toLowerCase(java.util.Locale.ROOT);
    String path = expectation.substring(separator + 1);
    return paths.get(path) instanceof Map<?, ?> operations && operations.containsKey(method);
  }

  public record InitialFailure(
      String outcome, String code, String detail, String command, List<String> failedTargets) {
    public InitialFailure {
      failedTargets = failedTargets == null ? List.of() : List.copyOf(failedTargets);
    }

    public boolean observed() {
      return "FAILURE_OBSERVED".equals(outcome);
    }
  }
}
