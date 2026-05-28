package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Paths;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.smoketest.WorkspaceTestRunner;
import com.navneet.executespec.testgen.model.TestExecutionSummary;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins for the BUILD-mode TDD-iteration tool (RL-2026-05-052). The contract is small but
 * load-bearing: flag-off short-circuits before the runner; flag-on delegates; runner empty →
 * informative envelope (not an error); runner non-empty → parsed counts with verified flag.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkspaceRunTestsTool — flag gate + envelope shape")
class WorkspaceRunTestsToolTest {

  @Mock private WorkspaceTestRunner runner;
  @Mock private FeatureFlagService featureFlagService;
  private final ObjectMapper json = new ObjectMapper();

  private WorkspaceRunTestsTool tool;
  private ExecutionContext ctx;

  @BeforeEach
  void setUp() {
    tool = new WorkspaceRunTestsTool(runner, featureFlagService, json);
    ctx = new ExecutionContext("run-1", "task-1", Paths.get("/tmp/ws"));
  }

  @Test
  @DisplayName("flag OFF → short-circuit, runner never called, available=false envelope")
  void flagOffShortCircuits() throws Exception {
    when(featureFlagService.isEnabled(WorkspaceRunTestsTool.FLAG_BUILD_TDD)).thenReturn(false);

    ToolResult result = tool.execute(json.createObjectNode(), ctx);
    JsonNode env = asOkJson(result);

    assertThat(env.get("available").asBoolean()).isFalse();
    assertThat(env.get("summary").asText()).contains("disabled");
    verify(runner, never()).runTests(anyString(), any());
  }

  @Test
  @DisplayName("flag ON + runner empty → available=true, verified=false, helpful message")
  void noRunnerYieldsInformativeOk() throws Exception {
    when(featureFlagService.isEnabled(WorkspaceRunTestsTool.FLAG_BUILD_TDD)).thenReturn(true);
    when(runner.runTests(anyString(), any())).thenReturn(Optional.empty());

    ToolResult result = tool.execute(json.createObjectNode(), ctx);
    JsonNode env = asOkJson(result);

    assertThat(env.get("available").asBoolean()).isTrue();
    assertThat(env.get("verified").asBoolean()).isFalse();
    assertThat(env.get("summary").asText()).contains("no recognized test runner");
  }

  @Test
  @DisplayName("flag ON + all-pass summary → verified=true and counts surface")
  void allPassYieldsVerifiedTrue() throws Exception {
    when(featureFlagService.isEnabled(WorkspaceRunTestsTool.FLAG_BUILD_TDD)).thenReturn(true);
    TestExecutionSummary summary =
        TestExecutionSummary.builder()
            .totalTests(8)
            .passed(8)
            .failed(0)
            .skipped(0)
            .errors(0)
            .executionDurationMs(1234L)
            .build();
    when(runner.runTests(anyString(), any())).thenReturn(Optional.of(summary));

    ToolResult result = tool.execute(json.createObjectNode(), ctx);
    JsonNode env = asOkJson(result);

    assertThat(env.get("verified").asBoolean()).isTrue();
    assertThat(env.get("passed").asInt()).isEqualTo(8);
    assertThat(env.get("failed").asInt()).isZero();
    assertThat(env.get("total").asInt()).isEqualTo(8);
    assertThat(env.get("durationMs").asLong()).isEqualTo(1234L);
  }

  @Test
  @DisplayName("flag ON + failure → verified=false even though tests ran")
  void failedTestsYieldVerifiedFalse() throws Exception {
    when(featureFlagService.isEnabled(WorkspaceRunTestsTool.FLAG_BUILD_TDD)).thenReturn(true);
    TestExecutionSummary summary =
        TestExecutionSummary.builder()
            .totalTests(5)
            .passed(3)
            .failed(2)
            .errors(0)
            .skipped(0)
            .build();
    when(runner.runTests(anyString(), any())).thenReturn(Optional.of(summary));

    ToolResult result = tool.execute(json.createObjectNode(), ctx);
    JsonNode env = asOkJson(result);

    assertThat(env.get("verified").asBoolean()).isFalse();
    assertThat(env.get("failed").asInt()).isEqualTo(2);
  }

  @Test
  @DisplayName("flag ON + zero-total → verified=false (no tests means nothing was verified)")
  void zeroTotalIsNotVerified() throws Exception {
    when(featureFlagService.isEnabled(WorkspaceRunTestsTool.FLAG_BUILD_TDD)).thenReturn(true);
    TestExecutionSummary summary =
        TestExecutionSummary.builder().totalTests(0).passed(0).failed(0).build();
    when(runner.runTests(anyString(), any())).thenReturn(Optional.of(summary));

    ToolResult result = tool.execute(json.createObjectNode(), ctx);
    JsonNode env = asOkJson(result);

    assertThat(env.get("verified").asBoolean()).isFalse();
    assertThat(env.get("total").asInt()).isZero();
  }

  @Test
  @DisplayName("null FeatureFlagService → treated as flag-on (test-friendly fallback)")
  void nullFlagServiceTreatedAsOn() throws Exception {
    WorkspaceRunTestsTool noFlagTool = new WorkspaceRunTestsTool(runner, null, json);
    when(runner.runTests(anyString(), any())).thenReturn(Optional.empty());

    ToolResult result = noFlagTool.execute(json.createObjectNode(), ctx);
    JsonNode env = asOkJson(result);

    assertThat(env.get("available").asBoolean()).isTrue();
  }

  private JsonNode asOkJson(ToolResult result) throws Exception {
    assertThat(result).isInstanceOf(ToolResult.Ok.class);
    ToolResult.Ok ok = (ToolResult.Ok) result;
    return json.readTree(ok.content());
  }
}
