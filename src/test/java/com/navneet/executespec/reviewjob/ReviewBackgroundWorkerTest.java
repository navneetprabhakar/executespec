package com.navneet.executespec.reviewjob;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.codereview.CodeReviewAgent;
import com.navneet.executespec.codereview.CodeReviewService;
import com.navneet.executespec.codereview.SemgrepRunner;
import com.navneet.executespec.prreview.PrReviewService;

import org.junit.jupiter.api.Test;

class ReviewBackgroundWorkerTest {

  @Test
  void restartPublishesStagedResultWithoutRepeatingProviderWork() throws Exception {
    ReviewBackgroundJobStore store = mock(ReviewBackgroundJobStore.class);
    CodeReviewService codeReview = mock(CodeReviewService.class);
    PrReviewService prReview = mock(PrReviewService.class);
    ObjectMapper mapper = new ObjectMapper();
    var prepared =
        new CodeReviewService.DurableCodeReviewResult(
            new SemgrepRunner.ScanResult(false, List.of(), 0, "test", 1),
            new CodeReviewAgent.AgentResult(
                "summary", List.of(), List.of(), 1, "deepseek", "deepseek-v4-pro-native"),
            0,
            0,
            CodeReviewAgent.ScanCoverage.COMPLETE, // RR-1696
            1);
    ReviewBackgroundJobStore.Job job =
        new ReviewBackgroundJobStore.Job(
            UUID.randomUUID(),
            ReviewBackgroundJobStore.Type.CODE_REVIEW,
            UUID.randomUUID(),
            "project-1",
            "org-1",
            UUID.randomUUID(),
            "user-1",
            "review:1",
            "DEEPSEEK",
            "deepseek-v4-pro-native",
            "llmctx-1",
            "CUSTOMER",
            "org-1",
            "{\"repoFullName\":\"owner/repo\",\"sha\":\"abc\"}",
            mapper.writeValueAsString(prepared),
            Instant.now(),
            ReviewBackgroundJobStore.Status.RUNNING,
            2,
            3,
            Instant.now(),
            "worker",
            2,
            Instant.now().plusSeconds(120),
            Instant.now(),
            null,
            null,
            null,
            Instant.now(),
            Instant.now(),
            null);
    ReviewBackgroundJobStore.Lease lease =
        new ReviewBackgroundJobStore.Lease(job, "worker", job.leaseToken(), job.leaseExpiresAt());
    when(store.cancellationRequested(lease)).thenReturn(false);
    when(store.heartbeat(lease, ReviewBackgroundWorker.LEASE)).thenReturn(true);

    ReviewBackgroundWorker worker = new ReviewBackgroundWorker(store, codeReview, prReview, mapper);
    try {
      worker.process(lease);
    } finally {
      worker.shutdown();
    }

    verify(codeReview, never()).runDurableJob(any(), any(), any(), any());
    verify(codeReview).publishDurableResult(lease, prepared);
    verify(store, never()).stageResult(any(), any());
  }

  @Test
  void cancellationDelegatesTheOnlyTerminalWriteToAtomicStoreTransition() {
    ReviewBackgroundJobStore store = mock(ReviewBackgroundJobStore.class);
    CodeReviewService codeReview = mock(CodeReviewService.class);
    PrReviewService prReview = mock(PrReviewService.class);
    ReviewBackgroundJobStore.Lease lease =
        lease("{\"repoFullName\":\"owner/repo\",\"sha\":\"abc\"}", 1, 3);
    when(store.cancellationRequested(lease)).thenReturn(true);

    ReviewBackgroundWorker worker =
        new ReviewBackgroundWorker(store, codeReview, prReview, new ObjectMapper());
    try {
      worker.process(lease);
    } finally {
      worker.shutdown();
    }

    verify(store).finishCancellation(lease);
    verifyNoInteractions(codeReview, prReview);
  }

  @Test
  void exhaustedFailureDelegatesTheOnlyTerminalWriteToAtomicStoreTransition() {
    ReviewBackgroundJobStore store = mock(ReviewBackgroundJobStore.class);
    CodeReviewService codeReview = mock(CodeReviewService.class);
    PrReviewService prReview = mock(PrReviewService.class);
    ReviewBackgroundJobStore.Lease lease = lease("{}", 3, 3);
    when(store.cancellationRequested(lease)).thenReturn(false);
    when(store.heartbeat(lease, ReviewBackgroundWorker.LEASE)).thenReturn(true);
    when(store.failOrRequeue(
            eq(lease), eq("INVALID_JOB_PAYLOAD"), any(), eq(ReviewBackgroundWorker.RETRY_DELAY)))
        .thenReturn(ReviewBackgroundJobStore.Status.FAILED);

    ReviewBackgroundWorker worker =
        new ReviewBackgroundWorker(store, codeReview, prReview, new ObjectMapper());
    try {
      worker.process(lease);
    } finally {
      worker.shutdown();
    }

    verify(store)
        .failOrRequeue(
            eq(lease), eq("INVALID_JOB_PAYLOAD"), any(), eq(ReviewBackgroundWorker.RETRY_DELAY));
    verifyNoInteractions(codeReview, prReview);
  }

  private static ReviewBackgroundJobStore.Lease lease(
      String payload, int attemptCount, int maxAttempts) {
    ReviewBackgroundJobStore.Job job =
        new ReviewBackgroundJobStore.Job(
            UUID.randomUUID(),
            ReviewBackgroundJobStore.Type.CODE_REVIEW,
            UUID.randomUUID(),
            "project-1",
            "org-1",
            UUID.randomUUID(),
            "user-1",
            "review:test:" + UUID.randomUUID(),
            "DEEPSEEK",
            "deepseek-v4-pro-native",
            "llmctx-1",
            "CUSTOMER",
            "org-1",
            payload,
            null,
            null,
            ReviewBackgroundJobStore.Status.RUNNING,
            attemptCount,
            maxAttempts,
            Instant.now(),
            "worker",
            2,
            Instant.now().plusSeconds(120),
            Instant.now(),
            null,
            null,
            null,
            Instant.now(),
            Instant.now(),
            null);
    return new ReviewBackgroundJobStore.Lease(
        job, "worker", job.leaseToken(), job.leaseExpiresAt());
  }
}
