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
import com.navneet.executespec.smoketest.BuildCommand;
import com.navneet.executespec.smoketest.BuildCommandResolver;
import com.navneet.executespec.smoketest.BuildVerificationResult;
import com.navneet.executespec.smoketest.TestCommandResolver;
import com.navneet.executespec.smoketest.TestCommandResolver.TestCommand;
import com.navneet.executespec.smoketest.WorkspaceTestRunner;
import com.navneet.executespec.smoketest.WorkspaceTestRunner.DetailedResult;
import com.navneet.executespec.smoketest.WorkspaceTestRunner.ExecutionEvidence;
import com.navneet.executespec.smoketest.WorkspaceTestRunner.ExecutionStatus;
import com.navneet.executespec.testgen.model.TestExecutionSummary;
import com.navneet.executespec.tool.BuildExecutionEvidence;
import com.navneet.executespec.tool.BuildProjectTool;
import com.navneet.executespec.tool.ExecutionContext;

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
  private final PythonHttpRuntimeAcceptanceService pythonHttpRuntime;
  private final ObjectMapper objectMapper;
  private final TaskAcceptanceDisposableRunner disposableRunner;

  /**
   * Produces the deterministic red signal that must exist before a schema-v2 worker can dispatch.
   * Missing required paths are executable file assertions. Existing-code tasks instead run the
   * frozen build/test surface; a green workspace is not silently relabelled as failure-first.
   */
  public InitialFailure probeInitial(ExecutionContext context, TaskAcceptanceContract contract) {
    return disposableRunner.probe(
        context, disposable -> probeInitialInDisposable(disposable, contract));
  }

  private InitialFailure probeInitialInDisposable(
      ExecutionContext context, TaskAcceptanceContract contract) {
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
    if (!BootstrapArtifactIntegrity.matches(context.workspaceRoot(), contract)) {
      return new InitialFailure(
          "UNAVAILABLE",
          "INITIAL_BOOTSTRAP_ARTIFACT_DRIFT",
          "Server-owned bootstrap artifacts no longer match the frozen contract",
          null,
          List.copyOf(contract.immutableBootstrapArtifactDigests().keySet()));
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
    List<String> missing = missingRequiredPaths(context.workspaceRoot(), contract.requiredPaths());
    if (!missing.isEmpty()) {
      return new InitialFailure(
          "FAILURE_OBSERVED",
          "REQUIRED_PATH_MISSING",
          "Required path assertions are red: " + missing,
          null,
          missing);
    }
    context.assertWorkspaceGenerationDispatch(WorkspaceGenerationDispatchAuthority.Phase.MUTATION);
    BuildExecutionEvidence buildEvidence = buildProjectTool.executeForVerification(context);
    String buildOutput =
        buildEvidence.output() == null ? buildEvidence.detail() : buildEvidence.output();
    BuildVerificationResult parsed = buildEvidence.verification();
    if (buildEvidence.environmentFailure()) {
      return new InitialFailure(
          "UNAVAILABLE",
          "INITIAL_BUILD_" + buildEvidence.disposition().name(),
          bounded(buildOutput),
          build.command(),
          List.of());
    }
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
      if ("OPTIONAL".equals(contract.testRequirement())) {
        return new InitialFailure(
            "ALREADY_PASSED",
            "BASELINE_BUILD_PASSED",
            bounded(buildOutput),
            build.command(),
            List.of());
      }
      return new InitialFailure(
          "UNAVAILABLE",
          "INITIAL_TEST_COMMAND_UNAVAILABLE",
          "No executable server-resolved test command is available",
          null,
          List.of());
    }
    context.assertWorkspaceGenerationDispatch(WorkspaceGenerationDispatchAuthority.Phase.MUTATION);
    ExecutionEvidence tested = testRunner.executeTests(context);
    if (tested.result() == null) {
      return new InitialFailure(
          "UNAVAILABLE",
          "INITIAL_TEST_" + tested.status().name(),
          tested.detail(),
          tested.command(),
          List.of());
    }
    DetailedResult detail = tested.result();
    TestExecutionSummary summary = detail.summary();
    boolean red = detail.exitCode() != 0 || summary.getFailed() > 0 || summary.getErrors() > 0;
    return new InitialFailure(
        red ? "FAILURE_OBSERVED" : "ALREADY_PASSED",
        red ? "INITIAL_TESTS_FAILED" : "NO_INITIAL_FAILURE",
        bounded(detail.outputTail()),
        detail.command(),
        List.of(),
        summary.getPassed(),
        summary.getFailed(),
        summary.getErrors(),
        summary.getTotalTests());
  }

  public WorkspaceGenerationLifecycle.Verification verify(
      ExecutionContext context, TaskAcceptanceContract contract, List<String> actualChangedFiles) {
    return disposableRunner.verify(
        context,
        contract,
        disposable -> verifyInDisposable(disposable, contract, actualChangedFiles));
  }

  private WorkspaceGenerationLifecycle.Verification verifyInDisposable(
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
    boolean candidateTestsPresent =
        changedFiles.stream().anyMatch(TaskAcceptanceVerifier::isTestPath);
    boolean schemaV2 = contract.schemaVersion() >= 2;
    boolean requiredPathsPresent =
        requiredPathsPresent(context.workspaceRoot(), contract.requiredPaths());
    boolean frozenBuildMatches = !schemaV2 || matches(contract.buildCommand(), build);
    boolean frozenTestMatches = !schemaV2 || matches(contract.testCommand(), test.orElse(null));

    Map<String, Object> commandContract = new LinkedHashMap<>();
    commandContract.put("schemaVersion", contract.schemaVersion());
    commandContract.put("commandClass", "BROAD_VERIFICATION");
    commandContract.put("executionBoundary", "DISPOSABLE_EXACT_GENERATION");
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

    context.assertWorkspaceGenerationDispatch(
        WorkspaceGenerationDispatchAuthority.Phase.VERIFICATION);
    BuildExecutionEvidence buildEvidence = buildProjectTool.executeForVerification(context);
    String buildOutput =
        buildEvidence.output() == null ? buildEvidence.detail() : buildEvidence.output();
    BuildVerificationResult parsed = buildEvidence.verification();
    boolean noOp = "fallback:no-op".equals(build.sourceDescription());
    boolean exactResolvedBuild =
        buildOutput.contains("command=" + build.command() + "\n")
            && buildOutput.contains("source=" + build.sourceDescription());
    boolean buildPassed =
        buildEvidence.disposition() == BuildExecutionEvidence.Disposition.EXECUTED
            && parsed.passed()
            && !noOp
            && exactResolvedBuild
            && frozenBuildMatches
            && requiredPathsPresent;
    boolean bootstrapArtifactsMatch =
        BootstrapArtifactIntegrity.matches(context.workspaceRoot(), contract);
    String outcome;

    boolean pythonHttpRuntimeRequired =
        "HTTP_SERVICE".equals(contract.runtimeSurface())
            && "PYTHON".equals(contract.baselineKind())
            && !contract.endpointExpectations().isEmpty();
    Optional<PythonHttpRuntimeAcceptanceService.Result> runtimeResult = Optional.empty();
    Optional<DetailedResult> testResult = Optional.empty();
    ExecutionEvidence testEvidence = null;
    if (buildPassed && test.isPresent() && bootstrapArtifactsMatch) {
      context.assertWorkspaceGenerationDispatch(
          WorkspaceGenerationDispatchAuthority.Phase.VERIFICATION);
      testEvidence = testRunner.executeTests(context);
      testResult = Optional.ofNullable(testEvidence.result());
    }
    if (buildPassed && pythonHttpRuntimeRequired && bootstrapArtifactsMatch) {
      context.assertWorkspaceGenerationDispatch(
          WorkspaceGenerationDispatchAuthority.Phase.VERIFICATION);
      runtimeResult = Optional.of(pythonHttpRuntime.verify(context));
    }
    TestExecutionSummary summary = testResult.map(DetailedResult::summary).orElse(null);
    boolean workspaceTestsExecuted = testResult.isPresent();
    boolean workspaceTestsPassed =
        summary != null
            && testResult.get().exitCode() == 0
            && summary.getTotalTests() > 0
            && summary.getPassed() > 0
            && summary.getFailed() == 0
            && summary.getErrors() == 0;
    boolean serverOwnedRuntimeCommand =
        pythonHttpRuntimeRequired
            && contract
                .immutableBootstrapArtifactDigests()
                .containsKey(".executespec/bootstrap/test_acceptance.py")
            && runtimeResult
                .map(
                    result ->
                        PythonHttpRuntimeAcceptanceService.RUNTIME_COMMAND.equals(
                            result.evidence().get("runtimeCommand")))
                .orElse(false);
    boolean runtimeContractExecuted =
        serverOwnedRuntimeCommand
            && runtimeResult.map(PythonHttpRuntimeAcceptanceService.Result::executed).orElse(false);
    boolean runtimeContractPassed =
        runtimeContractExecuted
            && runtimeResult.map(PythonHttpRuntimeAcceptanceService.Result::passed).orElse(false);
    boolean testsExecuted =
        workspaceTestsExecuted && (!pythonHttpRuntimeRequired || runtimeContractExecuted);
    boolean testsPassed =
        workspaceTestsPassed && (!pythonHttpRuntimeRequired || runtimeContractPassed);
    AcceptanceProofPolicy.Decision proof =
        AcceptanceProofPolicy.evaluate(
            contract.requiredProofStrength(),
            buildPassed,
            testsExecuted,
            testsPassed,
            runtimeContractPassed,
            false);
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
            candidateTestsPresent,
            runtimeContractPassed,
            bootstrapArtifactsMatch);

    if (runtimeResult
        .map(result -> result.status() == PythonHttpRuntimeAcceptanceService.Status.FAILED)
        .orElse(false)) {
      outcome = "FAILED";
    } else if (schemaV2 && checkEvaluation.status() == TaskAcceptanceCheckEvaluator.Status.FAILED) {
      outcome = "FAILED";
    } else if (schemaV2
        && checkEvaluation.status() == TaskAcceptanceCheckEvaluator.Status.UNAVAILABLE) {
      outcome = "UNAVAILABLE";
    } else if (!mutationPresent) {
      outcome = "FAILED";
    } else if (buildEvidence.environmentFailure()
        || noOp
        || parsed.status() == BuildVerificationResult.Status.UNAVAILABLE) {
      outcome = "UNAVAILABLE";
    } else if (!buildPassed) {
      outcome = "FAILED";
    } else if (testRequired && test.isEmpty()) {
      outcome = "UNAVAILABLE";
    } else if (testRequired && !testsPassed) {
      outcome = testsExecuted ? "FAILED" : "UNAVAILABLE";
    } else if (test.isPresent() && testsExecuted && !testsPassed) {
      outcome = "FAILED";
    } else if (!proof.sufficient()) {
      outcome = "UNAVAILABLE";
    } else {
      outcome = "PASSED";
    }

    String failureCategory = null;
    String environmentCode = null;
    if ("UNAVAILABLE".equals(outcome)) {
      if (buildEvidence.environmentFailure()) {
        failureCategory =
            buildEvidence.disposition() == BuildExecutionEvidence.Disposition.MALFORMED_EVIDENCE
                ? "INTERNAL_PROTOCOL"
                : "ENVIRONMENT";
        environmentCode = "BUILD_" + buildEvidence.disposition().name();
      } else if (testEvidence != null && testEvidence.result() == null) {
        failureCategory =
            testEvidence.status() == ExecutionStatus.MALFORMED_EVIDENCE
                ? "INTERNAL_PROTOCOL"
                : "ENVIRONMENT";
        environmentCode = "TEST_" + testEvidence.status().name();
      } else if (runtimeResult
          .map(result -> result.status() == PythonHttpRuntimeAcceptanceService.Status.UNAVAILABLE)
          .orElse(false)) {
        failureCategory = "ENVIRONMENT";
        environmentCode = "RUNTIME_UNAVAILABLE";
      } else {
        failureCategory = "INTERNAL_PROTOCOL";
        environmentCode = "ACCEPTANCE_PROOF_UNAVAILABLE";
      }
    } else if ("FAILED".equals(outcome)) {
      failureCategory = "PRODUCT";
    }

    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("schemaVersion", contract.schemaVersion());
    evidence.put("commandClass", "BROAD_VERIFICATION");
    evidence.put("executionBoundary", "DISPOSABLE_EXACT_GENERATION");
    evidence.put("canonicalFallbackUsed", false);
    evidence.put("taskContractDigest", contract.digest());
    evidence.put("buildStatus", parsed == null ? null : parsed.status().name());
    evidence.put("buildExecutionDisposition", buildEvidence.disposition().name());
    evidence.put("buildEnvironmentDetail", buildEvidence.detail());
    evidence.put("buildExecutionBoundary", buildEvidence.executionBoundary());
    evidence.put("buildRetryable", buildEvidence.retryable());
    evidence.put("exactResolvedBuild", exactResolvedBuild);
    evidence.put("frozenBuildMatches", frozenBuildMatches);
    evidence.put("frozenTestMatches", frozenTestMatches);
    evidence.put("requiredPathsPresent", requiredPathsPresent);
    evidence.put("bootstrapArtifactsMatch", bootstrapArtifactsMatch);
    evidence.put("actualChangedFiles", changedFiles);
    evidence.put("actualCodeChanged", actualCodeChanged);
    evidence.put("candidateTestsPresent", candidateTestsPresent);
    evidence.put("buildExitCode", parsed == null ? null : parsed.exitCode());
    evidence.put("buildOutput", bounded(buildOutput));
    evidence.put("testsExecuted", testsExecuted);
    evidence.put("testExecutionStatus", testEvidence == null ? null : testEvidence.status().name());
    evidence.put("testEnvironmentDetail", testEvidence == null ? null : testEvidence.detail());
    evidence.put(
        "productVerificationFailure",
        testEvidence != null && testEvidence.status() == ExecutionStatus.PRODUCT_FAILED);
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
    evidence.put("runtimeContractExecuted", runtimeContractExecuted);
    evidence.put("runtimeContractPassed", runtimeContractPassed);
    evidence.put("serverOwnedRuntimeCommand", serverOwnedRuntimeCommand);
    evidence.put(
        "runtimeAcceptanceStatus",
        runtimeResult.map(result -> result.status().name()).orElse(null));
    evidence.put(
        "runtimeAcceptanceDetail",
        runtimeResult.map(PythonHttpRuntimeAcceptanceService.Result::detail).orElse(null));
    evidence.put(
        "runtimeAcceptanceEvidence",
        runtimeResult.map(PythonHttpRuntimeAcceptanceService.Result::evidence).orElse(Map.of()));
    evidence.put("requiredProofStrength", proof.required().name());
    evidence.put("achievedProofStrength", proof.achieved().name());
    evidence.put("proofStrengthSufficient", proof.sufficient());
    evidence.put("acceptanceCheckOutcome", checkEvaluation.status().name());
    evidence.put("acceptanceCheckResults", checkEvaluation.results());

    return new WorkspaceGenerationLifecycle.Verification(
        testsExecuted ? "BUILD_AND_TEST" : "BUILD_ONLY",
        json(commandContract),
        outcome,
        failureCategory,
        environmentCode,
        "DISPOSABLE_EXACT_GENERATION",
        buildPassed,
        testsExecuted,
        summary == null ? 0 : summary.getPassed(),
        summary == null ? 0 : summary.getFailed(),
        summary == null ? 0 : summary.getErrors(),
        summary == null ? 0 : summary.getSkipped(),
        json(evidence),
        proof.required().name(),
        proof.achieved().name(),
        started,
        Instant.now());
  }

  private static boolean isTestPath(String changedFile) {
    if (changedFile == null || changedFile.isBlank()) return false;
    String path = changedFile.replaceFirst("^[0-9]+:", "").replace('\\', '/').toLowerCase();
    if (path.startsWith(".executespec/bootstrap/")) return false;
    String name = path.substring(path.lastIndexOf('/') + 1);
    return name.startsWith("test_")
        || name.endsWith("_test.py")
        || name.endsWith(".test.js")
        || name.endsWith(".test.mjs")
        || name.endsWith(".test.ts")
        || name.endsWith(".test.tsx")
        || name.endsWith(".spec.js")
        || name.endsWith(".spec.ts")
        || name.endsWith("test.java")
        || name.endsWith("_test.go");
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

  public record InitialFailure(
      String outcome,
      String code,
      String detail,
      String command,
      List<String> failedTargets,
      int passed,
      int failed,
      int errors,
      int total,
      String commandClass) {
    public InitialFailure(
        String outcome,
        String code,
        String detail,
        String command,
        List<String> failedTargets,
        int passed,
        int failed,
        int errors,
        int total) {
      this(
          outcome,
          code,
          detail,
          command,
          failedTargets,
          passed,
          failed,
          errors,
          total,
          "REPRODUCTION");
    }

    public InitialFailure(
        String outcome, String code, String detail, String command, List<String> failedTargets) {
      this(
          outcome,
          code,
          detail,
          command,
          failedTargets,
          0,
          "FAILURE_OBSERVED".equals(outcome) ? 1 : 0,
          0,
          "FAILURE_OBSERVED".equals(outcome) ? 1 : 0,
          "REPRODUCTION");
    }

    public InitialFailure {
      failedTargets = failedTargets == null ? List.of() : List.copyOf(failedTargets);
      if (passed < 0 || failed < 0 || errors < 0 || total < 0) {
        throw new IllegalArgumentException("initial verification counts must be non-negative");
      }
      if (passed + failed + errors > total) {
        throw new IllegalArgumentException("initial verification counts exceed total");
      }
      if (!"REPRODUCTION".equals(commandClass)) {
        throw new IllegalArgumentException("initial verification command must be REPRODUCTION");
      }
    }

    public boolean observed() {
      return "FAILURE_OBSERVED".equals(outcome);
    }

    /** A healthy baseline is valid change/repair input, never fabricated failing evidence. */
    public boolean admits(TaskAcceptanceContract contract) {
      return observed() || (contract.schemaVersion() >= 3 && "ALREADY_PASSED".equals(outcome));
    }
  }
}
