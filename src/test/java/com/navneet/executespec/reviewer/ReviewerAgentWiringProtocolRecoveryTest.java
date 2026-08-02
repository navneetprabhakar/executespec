package com.navneet.executespec.reviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.PromptAssembler;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.engine.WorkerAgent;
import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.engine.WorkerTaskRequest;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.tool.BuildProjectTool;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.ToolResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class ReviewerAgentWiringProtocolRecoveryTest {

  private ReviewerAgent agent;

  @AfterEach
  void shutdown() {
    if (agent != null) agent.shutdownExecutor();
  }

  @Test
  void emptyTerminalWiringGetsOneSameRouteRecoveryBeforeCleanIsAccepted(@TempDir Path workspace) {
    WorkerAgent worker = mock(WorkerAgent.class);
    WorkerResult general = completed("VERDICT: CLEAN\nGeneral review complete.");
    WorkerResult emptyWiring = completed("");
    WorkerResult recoveredWiring = completed("VERDICT: CLEAN\nWiring revalidated.");
    when(worker.run(any())).thenReturn(general, emptyWiring, recoveredWiring);

    BuildProjectTool build = mock(BuildProjectTool.class);
    when(build.execute(any(), any()))
        .thenReturn(
            ToolResult.ok(
                "command=mvn -q test\n"
                    + "source=inferred:maven\n"
                    + "smokeSignal=EXECUTED\n"
                    + "noOp=false\n"
                    + "exit=0"));
    PromptAssembler prompts = mock(PromptAssembler.class);
    when(prompts.assemble(anyString())).thenReturn("review system prompt");
    PostEditFormatter formatter = mock(PostEditFormatter.class);
    when(formatter.run(any(), anyString()))
        .thenReturn(new PostEditFormatter.Result(0, List.of(), List.of()));

    agent =
        new ReviewerAgent(
            worker,
            build,
            new ObjectMapper(),
            mock(Metrics.class),
            mock(FeatureFlagService.class),
            prompts,
            formatter,
            null);
    agent.initExecutor();

    ReviewerResult result =
        agent.review(
            ReviewerRequest.builder()
                .provider("anthropic")
                .model("claude-sonnet-5")
                // Same shape as InitialReviewerRequestPolicy: read-only, strict defaults false.
                .readOnly(true)
                .context(new ExecutionContext("run-recovery", "review", workspace))
                .build());

    assertThat(result.overall()).isEqualTo(ReviewerResult.Overall.CLEAN);
    assertThat(result.wiringPassSummary()).startsWith("VERDICT: CLEAN");
    assertThat(result.finalBuildPassed()).isTrue();
    ArgumentCaptor<WorkerTaskRequest> attempts = ArgumentCaptor.forClass(WorkerTaskRequest.class);
    verify(worker, times(3)).run(attempts.capture());
    assertThat(attempts.getAllValues())
        .allSatisfy(
            attempt -> {
              assertThat(attempt.provider()).isEqualTo("anthropic");
              assertThat(attempt.model()).isEqualTo("claude-sonnet-5");
            });
    assertThat(attempts.getAllValues().get(0).strictProvider()).isFalse();
    assertThat(attempts.getAllValues().get(1).strictProvider()).isFalse();
    assertThat(attempts.getAllValues().get(2).strictProvider()).isTrue();
    verify(prompts, times(2)).assemble("reviewer-wiring.md");
    verify(prompts).assemble("reviewer-wiring-recovery.md");
  }

  @Test
  void protocolRecoveryStripsMutationAuthorityFromMutableCaller() {
    ReviewerRequest source =
        ReviewerRequest.builder()
            .provider("anthropic")
            .model("claude-sonnet-5")
            .readOnly(false)
            .context(new ExecutionContext("run-resume", "review", Path.of("/tmp/review")))
            .build();

    ReviewerRequest recovery = ReviewerAgent.strictProtocolRecoveryRequest(source);

    assertThat(recovery.readOnly()).isTrue();
    assertThat(ReviewerAgent.reviewerToolNames("reviewer-wiring-recovery", recovery.readOnly()))
        .contains("readFile", "searchFiles", "buildProject", "completeTask")
        .doesNotContain("editFile", "writeFile");
  }

  private static WorkerResult completed(String summary) {
    return WorkerResult.builder()
        .outcome(WorkerResult.Outcome.COMPLETED)
        .completionSummary(summary)
        .toolCallLog(List.of())
        .build();
  }
}
