package com.navneet.executespec.v2.batch;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Parses terminal-state batch results and hands them off for local tool dispatch + realtime resume.
 * Plan 5 P1 scaffolding — {@link #resume} is a placeholder; P2 wires it to {@code
 * SequentialExecutorV2.continueFromBatch} + {@code WorkerAgentV2.resumeFromBatch}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class V2BatchResumeService {

  public void resume(String runId, List<V2BatchTaskResult> results) {
    log.info(
        "[V2BatchResumeService] scaffold called for run={} with {} result(s); "
            + "P2 wires actual resume.",
        runId,
        results == null ? 0 : results.size());
    throw new UnsupportedOperationException(
        "V2 batch resume not yet implemented — see docs/plans/v2-batch-execution-plan.md P2.");
  }
}
