package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * W4.2 (P6) — pins the {@code backgroundJob} tool: flag-gated (FORBIDDEN when off, drives {@code
 * enabled()} for the ToolRegistry binding gate), sandbox-validates start commands, delegates
 * status/logs/kill to the registry, and requires a jobId for those.
 */
@DisplayName("BackgroundJobTool (W4.2 — flag-gated job control)")
class BackgroundJobToolTest {

  private static final ObjectMapper M = new ObjectMapper();
  private FeatureFlagService flags;
  private JobRegistry jobs;
  private BackgroundJobTool tool;
  private final ExecutionContext ctx = new ExecutionContext("run-bt", "T1", Path.of("/tmp/ws-bt"));

  @BeforeEach
  void setUp() {
    flags = mock(FeatureFlagService.class);
    jobs = mock(JobRegistry.class);
    tool = new BackgroundJobTool(flags, jobs, new ShellSandboxPolicy());
  }

  private JsonNode args(String json) {
    try {
      return M.readTree(json);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void flagOn() {
    when(flags.isEnabled(BackgroundJobTool.FLAG)).thenReturn(true);
  }

  @Test
  @DisplayName(
      "flag OFF → FORBIDDEN (belt-and-suspenders; binding gate keeps it out of the schema)")
  void flagOff() {
    when(flags.isEnabled(BackgroundJobTool.FLAG)).thenReturn(false);
    ToolResult r = tool.execute(args("{\"action\":\"start\",\"command\":\"echo hi\"}"), ctx);
    assertThat(r).isInstanceOf(ToolResult.Err.class);
  }

  @Test
  @DisplayName("enabled() reflects the flag (drives ToolRegistry conditional bind)")
  void enabledReflectsFlag() {
    when(flags.isEnabled(BackgroundJobTool.FLAG)).thenReturn(false);
    assertThat(tool.enabled()).isFalse();
    when(flags.isEnabled(BackgroundJobTool.FLAG)).thenReturn(true);
    assertThat(tool.enabled()).isTrue();
  }

  @Test
  @DisplayName("start: sandbox-validates then launches, returns the jobId + pid")
  void start() throws Exception {
    flagOn();
    Process proc = mock(Process.class);
    when(proc.pid()).thenReturn(4242L);
    when(jobs.start(any(), eq("echo hi")))
        .thenReturn(
            new JobRegistry.Job(
                "job_abc123", "run-bt", proc, Path.of("/tmp/x.log"), "echo hi", 0L));
    ToolResult r = tool.execute(args("{\"action\":\"start\",\"command\":\"echo hi\"}"), ctx);
    assertThat(r).isInstanceOf(ToolResult.Ok.class);
    assertThat(((ToolResult.Ok) r).content()).contains("job_abc123").contains("4242");
  }

  @Test
  @DisplayName("start: a sandbox-blocked command is FORBIDDEN (never launched)")
  void startBlocked() {
    flagOn();
    ToolResult r = tool.execute(args("{\"action\":\"start\",\"command\":\"rm -rf /\"}"), ctx);
    assertThat(r).isInstanceOf(ToolResult.Err.class);
  }

  @Test
  @DisplayName("status/logs/kill require a jobId")
  void requiresJobId() {
    flagOn();
    assertThat(tool.execute(args("{\"action\":\"status\"}"), ctx))
        .isInstanceOf(ToolResult.Err.class);
  }

  @Test
  @DisplayName("status delegates to the registry")
  void statusDelegates() {
    flagOn();
    when(jobs.status("job_x")).thenReturn("running (pid=1, elapsed=2s)");
    ToolResult r = tool.execute(args("{\"action\":\"status\",\"jobId\":\"job_x\"}"), ctx);
    assertThat(((ToolResult.Ok) r).content()).contains("running");
  }

  @Test
  @DisplayName("unknown action → INVALID_ARGS")
  void unknownAction() {
    flagOn();
    assertThat(tool.execute(args("{\"action\":\"frobnicate\"}"), ctx))
        .isInstanceOf(ToolResult.Err.class);
  }
}
