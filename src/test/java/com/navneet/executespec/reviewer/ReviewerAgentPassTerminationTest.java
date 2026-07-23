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
  void abandonedPassThreadLosesWriteAuthorityMidFlight() throws Exception {
    // RR-272 / RR-1687 — the concrete claim: after the reviewer abandons a timed-out pass, a
    // writeFile from that still-running worker thread is REFUSED, not merely un-vouched-for. The
    // worker ignores interruption (an in-flight tool batch) and keeps probing its own write
    // authority; the value it observes must flip to a "revoked" refusal once the control thread
    // abandons it. Greenfield request, so the ONLY reason a write is refused is revocation.
    java.util.concurrent.atomic.AtomicReference<String> writeAfterAbandon =
        new java.util.concurrent.atomic.AtomicReference<>();
    AtomicBoolean release = new AtomicBoolean(false);
    when(worker.run(any()))
        .thenAnswer(
            inv -> {
              long deadline = System.currentTimeMillis() + 20_000;
              while (!release.get() && System.currentTimeMillis() < deadline) {
                String verdict =
                    com.navneet.executespec.tool.ReviewerMutationScope.validateWriteFile(
                        "src/anything.java", true, true);
                if (verdict != null) {
                  writeAfterAbandon.set(verdict);
                  break;
                }
                try {
                  Thread.sleep(20);
                } catch (InterruptedException ignored) {
                  // deliberately swallowed — models an in-flight tool batch
                }
              }
              return WorkerResult.builder().outcome(WorkerResult.Outcome.COMPLETED).build();
            });
    agent.setTerminationReserveSecondsForTest(3);

    try {
      agent.runPassWithTimeout(request(), 3, "reviewer-general", "system", 1, null);
      assertThat(writeAfterAbandon.get())
          .as("a writeFile from the abandoned pass thread must be refused")
          .isNotNull()
          .contains("revoked");
    } finally {
      release.set(true);
    }
  }

  @Test
  void abandonedPassMarksWorkspaceNonQuiescentSoPreReviewCaptureCanSkipIt() throws Exception {
    // RR-1686 — once a pass could not be proven terminated, its workspace is flagged so a
    // concurrent review of the same workspace does not run its pre-review build smoke over a tree
    // the abandoned thread may still hold. Unique workspace path so the JVM-shared marker set does
    // not carry state in from another test in this class.
    Path uniqueWorkspace = Path.of("target", "rr1686-" + System.nanoTime()).toAbsolutePath();
    ReviewerRequest req =
        ReviewerRequest.builder()
            .provider("anthropic")
            .model("claude-sonnet-5")
            .context(new ExecutionContext("run-rr1686", "review", uniqueWorkspace))
            .build();
    assertThat(ReviewerAgent.isWorkspaceNonQuiescent(req)).isFalse();

    AtomicBoolean release = new AtomicBoolean(false);
    when(worker.run(any()))
        .thenAnswer(
            inv -> {
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
          agent.runPassWithTimeout(req, 3, "reviewer-general", "system", 1, null);
      assertThat(run.workspaceQuiescent()).isFalse();
      assertThat(ReviewerAgent.isWorkspaceNonQuiescent(req)).isTrue();
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
