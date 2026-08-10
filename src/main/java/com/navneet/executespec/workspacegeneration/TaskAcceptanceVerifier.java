package com.navneet.executespec.workspacegeneration;

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

    Map<String, Object> commandContract = new LinkedHashMap<>();
    commandContract.put("schemaVersion", 1);
    commandContract.put("policy", contract.verificationPolicy());
    commandContract.put("buildSource", build.sourceDescription());
    commandContract.put("buildCommand", build.command());
    commandContract.put("buildTimeoutSeconds", build.timeoutSeconds());
    commandContract.put("testRequirement", contract.testRequirement());
    commandContract.put("effectiveTestRequirement", testRequired ? "REQUIRED" : "OPTIONAL");
    commandContract.put("actualChangedFiles", changedFiles);
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
    boolean buildPassed = parsed.passed() && !noOp && exactResolvedBuild;
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

    if (!mutationPresent) {
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
    evidence.put("schemaVersion", 1);
    evidence.put("taskContractDigest", contract.digest());
    evidence.put("buildStatus", parsed.status().name());
    evidence.put("exactResolvedBuild", exactResolvedBuild);
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

  private static String bounded(String value) {
    if (value == null || value.length() <= MAX_OUTPUT_CHARS) return value;
    return value.substring(0, 8_000)
        + "\n...[truncated]...\n"
        + value.substring(value.length() - 4_000);
  }
}
