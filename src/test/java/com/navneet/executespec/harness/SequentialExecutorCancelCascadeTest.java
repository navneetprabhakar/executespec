package com.navneet.executespec.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.agent.PlannedTask.Complexity;
import com.navneet.executespec.agent.WorkerAgent;
import com.navneet.executespec.agent.WorkerResult;
import com.navneet.executespec.agent.WorkerTaskRequest;
import com.navneet.executespec.context.ContextStore;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.tool.BuildProjectTool;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

/**
 * Pins the cancel-honour behaviour added 2026-05-10 to {@link SequentialExecutor#execute}.
 *
 * <p><b>Pre-fix:</b> the per-task {@code for} loop had zero {@code Thread.interrupted()} checks.
 * When {@code RunOrchestrator.cancel} called {@code future.cancel(true)} mid-task, the interrupt
 * surfaced only inside the next blocking LLM call. Each subsequent task got dispatched, the LLM
 * call threw {@code BackOffInterruptedException}, executeOne caught it, persisted FAILED, and the
 * outer loop body returned cleanly — only to step into the next task and repeat. Net effect on dev
 * run 7483896a (PR #99 analysis): cancel mid-T11 cascaded T11→T20 into FAILED in &lt;1 second,
 * overwriting their original {@code PARTIAL} / stagnation reasons.
 *
 * <p><b>Post-fix:</b> the loop checks {@code Thread.currentThread().isInterrupted()} at the top of
 * each iteration, breaks out, and re-asserts the interrupt so callers (RunOrchestrator's {@code
 * activeRuns} future) still see the thread as cancelled.
 *
 * <p>Note this test is structurally simpler than full {@code execute(...)} coverage: we use a stub
 * worker that pre-interrupts the thread on its first call, then count how many tasks the loop
 * dispatched before stopping. Pre-fix the count is 1+remainder (every task gets a worker call);
 * post-fix it's exactly 1 (the one that triggered the interrupt) or 0 (if interrupt was set before
 * the loop even started).
 */
class SequentialExecutorCancelCascadeTest {

  private final ExecutionContext ctx =
      new ExecutionContext("run-cancel-test", "__root__", Path.of("/tmp/seq-cancel-test"));

  @AfterEach
  void clearInterruptFlag() {
    // Guard against test leakage — every test in this file sets the interrupt; if it isn't
    // consumed by execute() the next test inherits it.
    Thread.interrupted();
  }

  @BeforeEach
  void clearBefore() {
    Thread.interrupted();
  }

  /**
   * Build a 5-task plan with logical ids T1..T5. Used to verify the loop stops mid-iteration once
   * the interrupt is set, rather than walking through all 5.
   */
  private static Plan fivePlan() {
    return Plan.builder()
        .planTitle("cancel-test")
        .tasks(
            List.of(
                PlannedTask.builder().id("T1").title("t1").complexity(Complexity.LOW).build(),
                PlannedTask.builder().id("T2").title("t2").complexity(Complexity.LOW).build(),
                PlannedTask.builder().id("T3").title("t3").complexity(Complexity.LOW).build(),
                PlannedTask.builder().id("T4").title("t4").complexity(Complexity.LOW).build(),
                PlannedTask.builder().id("T5").title("t5").complexity(Complexity.LOW).build()))
        .build();
  }

  /**
   * Build a {@link SequentialExecutor} wired with a worker that increments {@code workerCalls} on
   * every {@code run()} invocation. {@code preInterruptOnFirstCall=true} sets the thread's
   * interrupt flag inside the first worker call, simulating a cancel that lands mid-task.
   */
  private SequentialExecutor newExecutor(
      WorkerAgent worker, AgentTaskRepository taskRepo, ContextStore contextStore) {
    return new SequentialExecutor(
        worker,
        mock(BuildProjectTool.class),
        new ObjectMapper(),
        mock(com.navneet.executespec.observability.Metrics.class),
        mock(com.navneet.executespec.sse.SseBroadcaster.class),
        taskRepo,
        null, // modelResolver
        null, // retrievalService
        null, // inLoopTestRunner
        mock(com.navneet.executespec.config.featureflag.FeatureFlagService.class),
        contextStore,
        null /* costTracker */,
        mock(com.navneet.executespec.deployment.GcsTaskCheckpointService.class));
  }

  /**
   * Simulates "interrupt fires on iter #2's worker call". Counts how many times the worker was
   * invoked. Pre-fix this test would see 5 (every task ran). Post-fix it sees exactly 2 (T1
   * succeeded, T2 set the interrupt + threw, the loop saw isInterrupted at top of T3 and broke).
   */
  @Test
  void interrupt_fired_during_t2_worker_call_breaks_loop_before_t3() throws Exception {
    AgentTaskRepository taskRepo = mock(AgentTaskRepository.class);
    // Every persistTaskStatus → return an empty Optional so the executor's persist branch does
    // its no-op fallback. We're not asserting persistence, just dispatch-count.
    when(taskRepo.findByRunIdAndTaskId(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
        .thenReturn(Optional.empty());

    // smokeTests=false in this test so BuildProjectTool isn't invoked. The mock
    // is wired in newExecutor() but no method stubs are needed.

    AtomicInteger workerCalls = new AtomicInteger();
    WorkerAgent worker = mock(WorkerAgent.class);
    when(worker.run(ArgumentMatchers.any(WorkerTaskRequest.class), ArgumentMatchers.any()))
        .thenAnswer(
            inv -> {
              int n = workerCalls.incrementAndGet();
              if (n == 2) {
                // Simulate a cancel firing mid-T2: set the interrupt flag, then return a normal
                // outcome (executeOne wraps the worker so the interrupt would surface on the
                // NEXT blocking call, but without a real LLM round-trip we mimic the "cancel
                // landed during this task" state.)
                Thread.currentThread().interrupt();
              }
              return WorkerResult.builder()
                  .outcome(WorkerResult.Outcome.COMPLETED)
                  .iterations(1)
                  .toolCallsDispatched(0)
                  .totalLatencyMs(1)
                  .toolCallLog(List.of())
                  .build();
            });

    SequentialExecutor exec = newExecutor(worker, taskRepo, mock(ContextStore.class));

    var req =
        com.navneet.executespec.harness.ExecutionRequest.builder()
            .plan(fivePlan())
            .provider("anthropic")
            .model("claude-sonnet-4-6")
            .runContext(ctx)
            .maxIterationsPerTask(null)
            .enableSmokeTests(false)
            .designBlock(null)
            .build();

    exec.execute(req, null);

    // Post-fix: T1 (clean), T2 (sets interrupt), then loop top-check breaks before T3 fires its
    // worker. So workerCalls == 2.
    // Pre-fix: T1, T2 (with interrupt), T3 (would have run because no top-check), T4, T5 → 5.
    assertEquals(
        2,
        workerCalls.get(),
        "Loop should stop after the iteration that set the interrupt flag (pre-fix would dispatch"
            + " all 5 tasks)");
  }

  @Test
  void preset_interrupt_breaks_loop_before_first_task() throws Exception {
    AgentTaskRepository taskRepo = mock(AgentTaskRepository.class);
    when(taskRepo.findByRunIdAndTaskId(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
        .thenReturn(Optional.empty());

    AtomicInteger workerCalls = new AtomicInteger();
    WorkerAgent worker = mock(WorkerAgent.class);
    when(worker.run(ArgumentMatchers.any(WorkerTaskRequest.class), ArgumentMatchers.any()))
        .thenAnswer(
            inv -> {
              workerCalls.incrementAndGet();
              return WorkerResult.builder()
                  .outcome(WorkerResult.Outcome.COMPLETED)
                  .iterations(1)
                  .toolCallsDispatched(0)
                  .totalLatencyMs(1)
                  .toolCallLog(List.of())
                  .build();
            });

    SequentialExecutor exec = newExecutor(worker, taskRepo, mock(ContextStore.class));
    var req =
        com.navneet.executespec.harness.ExecutionRequest.builder()
            .plan(fivePlan())
            .provider("anthropic")
            .model("claude-sonnet-4-6")
            .runContext(ctx)
            .maxIterationsPerTask(null)
            .enableSmokeTests(false)
            .designBlock(null)
            .build();

    // Set interrupt BEFORE calling execute. The top-of-loop check should fire on i=0 and break
    // immediately.
    Thread.currentThread().interrupt();
    exec.execute(req, null);

    assertEquals(
        0,
        workerCalls.get(),
        "Pre-set interrupt should break the loop before any worker is called");
  }

  @Test
  void clean_run_no_interrupt_dispatches_all_tasks() throws Exception {
    AgentTaskRepository taskRepo = mock(AgentTaskRepository.class);
    when(taskRepo.findByRunIdAndTaskId(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
        .thenReturn(Optional.empty());

    AtomicInteger workerCalls = new AtomicInteger();
    WorkerAgent worker = mock(WorkerAgent.class);
    when(worker.run(ArgumentMatchers.any(WorkerTaskRequest.class), ArgumentMatchers.any()))
        .thenAnswer(
            inv -> {
              workerCalls.incrementAndGet();
              return WorkerResult.builder()
                  .outcome(WorkerResult.Outcome.COMPLETED)
                  .iterations(1)
                  .toolCallsDispatched(0)
                  .totalLatencyMs(1)
                  .toolCallLog(List.of())
                  .build();
            });

    SequentialExecutor exec = newExecutor(worker, taskRepo, mock(ContextStore.class));
    var req =
        com.navneet.executespec.harness.ExecutionRequest.builder()
            .plan(fivePlan())
            .provider("anthropic")
            .model("claude-sonnet-4-6")
            .runContext(ctx)
            .maxIterationsPerTask(null)
            .enableSmokeTests(false)
            .designBlock(null)
            .build();

    exec.execute(req, null);

    assertEquals(5, workerCalls.get(), "Without interrupt the loop dispatches every task");
    assertFalse(
        Thread.currentThread().isInterrupted(),
        "Clean run must not leave the thread in an interrupted state");
  }

  @Test
  void interrupt_flag_is_re_asserted_on_break() throws Exception {
    // After break, the thread's interrupt flag should still be set so RunOrchestrator's
    // future.cancel(true) propagation continues to work end-to-end.
    AgentTaskRepository taskRepo = mock(AgentTaskRepository.class);
    when(taskRepo.findByRunIdAndTaskId(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
        .thenReturn(Optional.empty());

    WorkerAgent worker = mock(WorkerAgent.class);
    when(worker.run(ArgumentMatchers.any(WorkerTaskRequest.class), ArgumentMatchers.any()))
        .thenAnswer(
            inv -> {
              Thread.currentThread().interrupt();
              return WorkerResult.builder()
                  .outcome(WorkerResult.Outcome.COMPLETED)
                  .iterations(1)
                  .toolCallsDispatched(0)
                  .totalLatencyMs(1)
                  .toolCallLog(List.of())
                  .build();
            });

    SequentialExecutor exec = newExecutor(worker, taskRepo, mock(ContextStore.class));
    var req =
        com.navneet.executespec.harness.ExecutionRequest.builder()
            .plan(fivePlan())
            .provider("anthropic")
            .model("claude-sonnet-4-6")
            .runContext(ctx)
            .maxIterationsPerTask(null)
            .enableSmokeTests(false)
            .designBlock(null)
            .build();

    exec.execute(req, null);

    assertTrue(
        Thread.currentThread().isInterrupted(),
        "The break must re-assert the interrupt flag so callers (Future.cancel) still see it");
  }
}
