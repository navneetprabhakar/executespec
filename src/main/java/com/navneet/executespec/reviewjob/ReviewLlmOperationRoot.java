package com.navneet.executespec.reviewjob;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import com.navneet.executespec.executioncontext.LlmExecutionOperationCoordinator;
import com.navneet.executespec.executioncontext.LlmExecutionOperationDescriptor;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Owns the one-shot logical LLM operation nested inside a durable review job. */
@Service
@RequiredArgsConstructor
class ReviewLlmOperationRoot {

  record Active(LlmExecutionOperationCoordinator.Lease lease, ExecutionContext context) {}

  private final LlmExecutionOperationCoordinator coordinator;

  Optional<Active> claim(
      ReviewBackgroundJobStore.Job job,
      String owner,
      Duration leaseDuration,
      boolean confirmedRetry) {
    LlmExecutionReference reference = reference(job);
    LlmExecutionOperationDescriptor descriptor =
        LlmExecutionOperationDescriptor.of(
            "review-job:" + job.id(), job.type().name(), immutableIdentity(job), 1);
    Optional<LlmExecutionOperationCoordinator.Lease> lease =
        coordinator.claimOrResume(reference, descriptor, owner, leaseDuration);
    if (lease.isEmpty() && confirmedRetry) {
      lease =
          Optional.of(coordinator.beginNewInvocation(reference, descriptor, owner, leaseDuration));
    }
    return lease.map(
        claimed ->
            new Active(
                claimed,
                coordinator.bind(
                    new ExecutionContext(
                        "review-job-" + job.id(),
                        job.type().name().toLowerCase(java.util.Locale.ROOT),
                        Path.of(System.getProperty("java.io.tmpdir", "/tmp"))
                            .toAbsolutePath()
                            .normalize(),
                        reference),
                    claimed)));
  }

  boolean heartbeat(Active active, Duration leaseDuration) {
    return coordinator.heartbeat(active.lease(), leaseDuration);
  }

  void complete(Active active) {
    if (!coordinator.complete(active.lease())) {
      throw new IllegalStateException("review LLM operation could not be completed");
    }
  }

  void terminate(Active active, String code, String message) {
    if (active == null) return;
    if (coordinator.acknowledgeCancellation(active.lease())) return;
    if (!coordinator.fail(active.lease(), code, message, false, null)) {
      throw new IllegalStateException("review LLM operation could not be terminalized");
    }
  }

  void requestCancellation(ReviewBackgroundJobStore.Job job) {
    coordinator.requestCancellation(reference(job), "review-job:" + job.id());
  }

  private static LlmExecutionReference reference(ReviewBackgroundJobStore.Job job) {
    return new LlmExecutionReference(
        job.executionContextId(),
        new LlmExecutionScope(
            LlmExecutionScope.ScopeMode.valueOf(job.executionScopeMode()),
            job.executionScopeOrgId()));
  }

  private static String immutableIdentity(ReviewBackgroundJobStore.Job job) {
    return String.join(
        "\n",
        job.id().toString(),
        job.type().name(),
        job.subjectId().toString(),
        job.projectId(),
        job.repoId().toString(),
        job.provider(),
        job.model(),
        job.executionContextId(),
        job.payload());
  }
}
