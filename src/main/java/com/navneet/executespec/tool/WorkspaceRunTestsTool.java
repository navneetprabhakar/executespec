package com.navneet.executespec.tool;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.smoketest.WorkspaceTestRunner;
import com.navneet.executespec.testgen.model.TestExecutionSummary;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Language-agnostic workspace-test runner exposed as a worker tool. Lets a BUILD-mode worker
 * validate its own tests in-loop, before {@code completeTask}, instead of only learning about test
 * failures from the post-completion scoring phase.
 *
 * <p>Wraps {@link WorkspaceTestRunner} — same resolver-derived command, same sandbox validation,
 * same cleanroom-container routing. The only differences vs the existing {@link RunTestsTool}:
 *
 * <ul>
 *   <li>{@code runTests} (pytest only, BYO test ids) is shaped for PATCH-mode bench runs where the
 *       failing test ids come from the harness.
 *   <li>{@code runWorkspaceTests} (this tool) runs the framework {@code TestCommandResolver}
 *       detected (pytest / mvn / npm / cargo / etc.) over the whole worker-written suite — what
 *       BUILD mode needs.
 * </ul>
 *
 * <p><b>Flag-gated:</b> {@code worker.build-tdd.enabled} (V217, default OFF). When off, every call
 * returns an informative Ok envelope with {@code "available": false} so the worker can learn the
 * tool isn't usable on this run rather than getting a hard error. Flip on per-run via the feature
 * flag admin endpoint to measure lift in an eval before any default change.
 *
 * <p><b>Why default OFF:</b> on-by-default would change worker behavior across every BUILD run,
 * potentially burning iter budget on a tool with unknown net impact. Default-OFF keeps production
 * byte-identical until eval data justifies it. Mirrors the rollout discipline used for {@code
 * worker.tool-compactor.enabled} (V216) and {@code scoring.real-test-exec} (V203 then defaulted
 * on).
 *
 * <p>RL-2026-05-052 — BUILD-mode TDD-iteration lever (mixed10 follow-up).
 */
@Component
@Slf4j
public class WorkspaceRunTestsTool implements Tool {

  /** Feature flag — see class javadoc. */
  public static final String FLAG_BUILD_TDD = "worker.build-tdd.enabled";

  private final WorkspaceTestRunner runner;
  private final FeatureFlagService featureFlagService;
  private final ObjectMapper json;

  @Autowired
  public WorkspaceRunTestsTool(
      WorkspaceTestRunner runner, FeatureFlagService featureFlagService, ObjectMapper json) {
    this.runner = runner;
    this.featureFlagService = featureFlagService;
    this.json = json;
  }

  @Override
  public String name() {
    return "runWorkspaceTests";
  }

  @Override
  public String description() {
    return "Runs the project's test suite using the resolver-detected framework "
        + "(pytest / mvn / gradle / npm / cargo / go test). Returns parsed pass/fail counts. "
        + "Use in BUILD mode after writing tests to validate them before completeTask — iterate "
        + "on failures rather than relying solely on the post-run scoring phase. No test-id "
        + "argument required (runs the whole suite). For PATCH-mode runs with a known failing-test "
        + "id, use runTests instead.";
  }

  @Override
  public String inputSchema() {
    return """
{
  "type": "object",
  "properties": {},
  "additionalProperties": false
}
""";
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    if (featureFlagService != null && !featureFlagService.isEnabled(FLAG_BUILD_TDD)) {
      ObjectNode disabled = json.createObjectNode();
      disabled.put("available", false);
      disabled.put(
          "summary",
          "runWorkspaceTests is disabled on this run (flag worker.build-tdd.enabled=false). "
              + "Tests will still be run by the scoring phase after completeTask.");
      return ToolResult.ok(disabled.toString());
    }

    Optional<TestExecutionSummary> opt = runner.runTests(ctx.runId(), ctx.workspaceRoot());
    if (opt.isEmpty()) {
      ObjectNode noRunner = json.createObjectNode();
      noRunner.put("available", true);
      noRunner.put("verified", false);
      noRunner.put(
          "summary",
          "no recognized test runner in the workspace, or test output was unparseable. "
              + "Confirm the framework is supported (pytest / mvn / gradle / npm / cargo / go) "
              + "and that tests + config files were written to disk before retrying.");
      return ToolResult.ok(noRunner.toString());
    }

    TestExecutionSummary s = opt.get();
    boolean verified = s.getFailed() == 0 && s.getErrors() == 0 && s.getTotalTests() > 0;

    ObjectNode result = json.createObjectNode();
    result.put("available", true);
    result.put("verified", verified);
    result.put(
        "summary",
        String.format(
            "%d passed, %d failed, %d skipped, %d errors of %d total in %dms",
            s.getPassed(),
            s.getFailed(),
            s.getSkipped(),
            s.getErrors(),
            s.getTotalTests(),
            s.getExecutionDurationMs()));
    result.put("total", s.getTotalTests());
    result.put("passed", s.getPassed());
    result.put("failed", s.getFailed());
    result.put("skipped", s.getSkipped());
    result.put("errors", s.getErrors());
    result.put("durationMs", s.getExecutionDurationMs());

    log.info(
        "runWorkspaceTests run={} task={} → {} passed, {} failed of {} (verified={})",
        ctx.runId(),
        ctx.taskId(),
        s.getPassed(),
        s.getFailed(),
        s.getTotalTests(),
        verified);

    return ToolResult.ok(result.toString());
  }
}
