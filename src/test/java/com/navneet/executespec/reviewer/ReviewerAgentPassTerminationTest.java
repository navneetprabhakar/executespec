package com.navneet.executespec.reviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.navneet.executespec.engine.WorkerAgent;
import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * RR-272 regression — a timed-out review pass holds the shared workspace and (in a brownfield run)
 * its write authority. {@code Future.cancel(true)} only raises the interrupt flag, so the reviewer
 * must PROVE the pass thread stopped before any later stage measures or publishes that workspace.
 */
class ReviewerAgentPassTerminationTest {

  private WorkerAgent worker;
  private ReviewerAgent agent;

  @BeforeEach
  void setUp() {
    worker = mock(WorkerAgent.class);
    agent = new ReviewerAgent(worker, null, null, null, null, null, null);
    agent.initExecutor();
  }

  @AfterEach
  void tearDown() {
    agent.shutdownExecutor();
  }

  private ReviewerRequest request() {
    return ReviewerRequest.builder()
        .provider("anthropic")
        .model("claude-sonnet-5")
        .context(new ExecutionContext("run-1", "review", Path.of(".").toAbsolutePath()))
        .build();
  }

  @Test
  void reportsQuiescentWhenTheCancelledPassActuallyStops() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    when(worker.run(any()))
        .thenAnswer(
            inv -> {
              entered.countDown();
              try {
                Thread.sleep(60_000);
              } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
              }
              return WorkerResult.builder().outcome(WorkerResult.Outcome.COMPLETED).build();
            });
    agent.setTerminationReserveSecondsForTest(10);

    ReviewerAgent.PassRun run =
        agent.runPassWithTimeout(request(), 3, "reviewer-general", "system", 1, null);

    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(run.result().outcome()).isEqualTo(WorkerResult.Outcome.ERROR);
    assertThat(run.workspaceQuiescent()).isTrue();
    assertThat(run.result().completionSummary()).doesNotContain("abandoned");
  }

  @Test
  void reportsNotQuiescentWhenTheCancelledPassKeepsRunningPastTheReserve() throws Exception {
    AtomicBoolean release = new AtomicBoolean(false);
    when(worker.run(any()))
        .thenAnswer(
            inv -> {
              // A worker that does not observe interruption — exactly the in-flight provider
              // response / tool batch the reviewer used to abandon and then build on top of.
              long deadline = System.currentTimeMillis() + 20_000;
              while (!release.get() && System.currentTimeMillis() < deadline) {
                try {
                  Thread.sleep(20);
                } catch (InterruptedException ignored) {
                  // deliberately swallowed
                }
              }
              return WorkerResult.builder().outcome(WorkerResult.Outcome.COMPLETED).build();
            });
    agent.setTerminationReserveSecondsForTest(1);

    try {
      ReviewerAgent.PassRun run =
          agent.runPassWithTimeout(request(), 3, "reviewer-general", "system", 1, null);

      assertThat(run.result().outcome()).isEqualTo(WorkerResult.Outcome.ERROR);
      assertThat(run.workspaceQuiescent()).isFalse();
      assertThat(run.result().completionSummary()).contains("did not terminate");
      assertThat(run.result().completionSummary()).contains("abandoned");
    } finally {
      release.set(true);
    }
  }

  @Test
  void abandonedReviewResultCarriesNoWorkspaceEvidence() {
    ReviewerResult abandoned =
        ReviewerResult.builder()
            .overall(ReviewerResult.Overall.REVIEW_FAILED)
            .workspaceQuiescent(false)
            .build();

    assertThat(abandoned.isWorkspaceQuiescent()).isFalse();
    assertThat(abandoned.finalBuildPassed()).isNull();
    assertThat(abandoned.finalBuildOutput()).isNull();
    assertThat(abandoned.finalTestsPassed()).isNull();
    assertThat(abandoned.finalTestsTotal()).isNull();
  }

  @Test
  void ordinaryReviewResultsRemainQuiescentByDefault() {
    assertThat(ReviewerResult.builder().overall(ReviewerResult.Overall.CLEAN).build())
        .matches(ReviewerResult::isWorkspaceQuiescent);
  }
}
