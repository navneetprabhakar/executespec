package com.navneet.executespec.reviewjob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.executioncontext.LlmExecutionOperationClaim;
import com.navneet.executespec.executioncontext.LlmExecutionOperationCoordinator;
import com.navneet.executespec.executioncontext.LlmExecutionOperationService;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;

import org.junit.jupiter.api.Test;

class ReviewLlmOperationRootTest {

  private static final Duration LEASE = Duration.ofMinutes(2);

  @Test
  void bindsOpaqueOperationAuthorityToTheReviewAgentContext() {
    LlmExecutionOperationService service = mock(LlmExecutionOperationService.class);
    ReviewBackgroundJobStore.Job job = job(null);
    LlmExecutionReference reference = reference(job);
    when(service.claimOrResume(eq(reference), any(), eq("worker:1"), eq(LEASE)))
        .thenReturn(Optional.of(claim(reference, 1)));
    ReviewLlmOperationRoot root =
        new ReviewLlmOperationRoot(new LlmExecutionOperationCoordinator(service));

    ReviewLlmOperationRoot.Active active = root.claim(job, "worker:1", LEASE, false).orElseThrow();

    assertThat(active.context().llmExecutionContextId()).isEqualTo(job.executionContextId());
    assertThat(active.context().llmExecutionOperationAuthority()).isNotNull();
    verify(service, never()).beginNewInvocation(any(), any(), any(), any());
  }

  @Test
  void uncertainExpiredAttemptIsNotAutomaticallyReplayed() {
    LlmExecutionOperationService service = mock(LlmExecutionOperationService.class);
    ReviewBackgroundJobStore.Job job = job("PROVIDER_TIMEOUT");
    LlmExecutionReference reference = reference(job);
    when(service.claimOrResume(eq(reference), any(), eq("worker:2"), eq(LEASE)))
        .thenReturn(Optional.empty());
    ReviewLlmOperationRoot root =
        new ReviewLlmOperationRoot(new LlmExecutionOperationCoordinator(service));

    assertThat(root.claim(job, "worker:2", LEASE, false)).isEmpty();

    verify(service, never()).beginNewInvocation(any(), any(), any(), any());
  }

  @Test
  void confirmedRetryStartsANewInvocationOnlyAfterPriorInvocationIsTerminal() {
    LlmExecutionOperationService service = mock(LlmExecutionOperationService.class);
    ReviewBackgroundJobStore.Job job = job("CONFIRMED_RETRY");
    LlmExecutionReference reference = reference(job);
    when(service.claimOrResume(eq(reference), any(), eq("worker:3"), eq(LEASE)))
        .thenReturn(Optional.empty());
    when(service.beginNewInvocation(eq(reference), any(), eq("worker:3"), eq(LEASE)))
        .thenReturn(claim(reference, 2));
    ReviewLlmOperationRoot root =
        new ReviewLlmOperationRoot(new LlmExecutionOperationCoordinator(service));

    ReviewLlmOperationRoot.Active active = root.claim(job, "worker:3", LEASE, true).orElseThrow();

    assertThat(active.context().llmExecutionOperationAuthority()).isNotNull();
    verify(service).beginNewInvocation(eq(reference), any(), eq("worker:3"), eq(LEASE));
  }

  private static LlmExecutionOperationClaim claim(LlmExecutionReference reference, int invocation) {
    return new LlmExecutionOperationClaim(
        reference, "llmop-review", invocation, 1, UUID.randomUUID());
  }

  private static LlmExecutionReference reference(ReviewBackgroundJobStore.Job job) {
    return new LlmExecutionReference(
        job.executionContextId(),
        new LlmExecutionScope(
            LlmExecutionScope.ScopeMode.valueOf(job.executionScopeMode()),
            job.executionScopeOrgId()));
  }

  private static ReviewBackgroundJobStore.Job job(String lastErrorCode) {
    Instant now = Instant.parse("2026-07-28T00:00:00Z");
    return new ReviewBackgroundJobStore.Job(
        UUID.randomUUID(),
        ReviewBackgroundJobStore.Type.CODE_REVIEW,
        UUID.randomUUID(),
        "project-1",
        "org-1",
        UUID.randomUUID(),
        "user-1",
        "code-review:project-1:repo-1:sha",
        "ANTHROPIC",
        "claude-sonnet-4-6",
        "llmctx-review",
        "CUSTOMER",
        "org-1",
        "{\"repoFullName\":\"owner/repo\",\"sha\":\"abc\"}",
        null,
        null,
        ReviewBackgroundJobStore.Status.RUNNING,
        1,
        1,
        now,
        "worker",
        1,
        now.plus(LEASE),
        now,
        null,
        lastErrorCode,
        null,
        now,
        now,
        null);
  }
}
