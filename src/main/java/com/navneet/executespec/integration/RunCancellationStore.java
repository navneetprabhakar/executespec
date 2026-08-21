package com.navneet.executespec.integration;

import java.time.Instant;

import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Narrow durable boundary for parent-run and task cancellation convergence. */
@Service
@RequiredArgsConstructor
public class RunCancellationStore {

  private final PipelineRunRepository runs;
  private final AgentTaskRepository tasks;

  /**
   * Atomically lets cancellation win the parent terminal claim and terminalizes every nonterminal
   * task. Executor interruption, filesystem rollback, events, and cost reconciliation happen only
   * after this transaction commits.
   */
  @Transactional
  public boolean cancel(String runId, String previousStatus, Instant now) {
    if (runs.claimTerminalTransitionWithReasons(
            runId, "CANCELLED", null, "Cancelled by user from status " + previousStatus, now)
        != 1) {
      return false;
    }
    tasks.cancelNonterminalForRun(runId, now);
    return true;
  }
}
