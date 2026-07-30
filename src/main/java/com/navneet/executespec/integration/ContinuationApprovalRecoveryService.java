package com.navneet.executespec.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves continuation-approval crash windows without replaying uncertain provider or workspace
 * side effects.
 *
 * <p>CLAIMED precedes the durable QUEUED boundary and is safe to release after expiry. QUEUED may
 * already have entered a mutating worker, so an expired receipt with no fresh run heartbeat fails
 * the run and invalidates publication evidence. A new run is required; the system never guesses
 * whether provider spend or file mutation occurred.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class ContinuationApprovalRecoveryService {

  static final String FAILURE_REASON = "CONTINUATION_APPROVAL_RECOVERY_UNSAFE";
  private static final Duration RUN_HEARTBEAT_FRESHNESS = Duration.ofMinutes(2);

  private final AgentTaskRepository taskRepository;
  private final PipelineRunRepository runRepository;
  private final ValidationReportRepository validationReportRepository;

  @Value("${execution.continuation.approval-stale-after:PT4H}")
  private Duration approvalStaleAfter;

  @Scheduled(
      initialDelayString = "${execution.continuation.approval-recovery-initial-delay-ms:60000}",
      fixedDelayString = "${execution.continuation.approval-recovery-interval-ms:60000}")
  void recoverStaleApprovals() {
    Instant now = Instant.now();
    Instant staleBefore = now.minus(approvalStaleAfter);
    for (AgentTask task :
        taskRepository
            .findByContinuationApprovalStatusAndContinuationApprovalUpdatedAtBefore(
                "CLAIMED", staleBefore)
            .stream()
            .limit(100)
            .toList()) {
      int released =
          taskRepository.releaseStaleClaimedContinuationApproval(
              task.getTaskId(),
              task.getContinuationApprovalId(),
              task.getContinuationApprovalProposalDigest(),
              staleBefore);
      if (released == 1) {
        log.warn(
            "Released stale pre-queue continuation approval run={} task={}",
            task.getRunId(),
            task.getTaskId());
      }
    }

    List<AgentTask> queued =
        taskRepository
            .findByContinuationApprovalStatusAndContinuationApprovalUpdatedAtBefore(
                "QUEUED", staleBefore)
            .stream()
            .limit(100)
            .toList();
    for (AgentTask task : queued) {
      var run = runRepository.findById(task.getRunId()).orElse(null);
      if (run != null
          && run.getExecutionHeartbeat() != null
          && run.getExecutionHeartbeat().isAfter(now.minus(RUN_HEARTBEAT_FRESHNESS))) {
        continue;
      }
      int failed =
          taskRepository.failStaleQueuedContinuationApproval(
              task.getTaskId(),
              task.getContinuationApprovalId(),
              task.getContinuationApprovalProposalDigest(),
              staleBefore,
              now);
      if (failed != 1) {
        continue;
      }
      validationReportRepository
          .findByRunId(task.getRunId())
          .ifPresent(
              report -> {
                report.setStatus("STALE");
                validationReportRepository.save(report);
              });
      runRepository.claimTerminalTransitionWithReasons(
          task.getRunId(), "FAILED", FAILURE_REASON, FAILURE_REASON, now);
      log.error(
          "Failed stale queued continuation approval run={} task={}; publication evidence"
              + " invalidated and a fresh run is required",
          task.getRunId(),
          task.getTaskId());
    }
  }
}
