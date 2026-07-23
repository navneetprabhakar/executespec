package com.navneet.executespec.prreview.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.project.pr.PullRequestRecord;
import com.navneet.executespec.project.pr.PullRequestRecordRepository;
import com.navneet.executespec.sse.SseBroadcaster;

import org.junit.jupiter.api.Test;

class PrCommentInboxServiceTest {

  private final PrCommentInboxRepository inboxRepo = mock(PrCommentInboxRepository.class);
  private final PullRequestRecordRepository prRecordRepo = mock(PullRequestRecordRepository.class);
  private final SseBroadcaster broadcaster = mock(SseBroadcaster.class);
  private final PrCommentInboxService service =
      new PrCommentInboxService(inboxRepo, prRecordRepo, broadcaster);

  private PrCommentInboxService.IngestRequest ingestRequest(String body) {
    return new PrCommentInboxService.IngestRequest(
        "acme/widget", 7, 99L, "src/Foo.java", 3, body, "octocat", "https://gh/comment/99");
  }

  private void trackedPr() {
    when(prRecordRepo.findByRepoFullNameAndPrNumber("acme/widget", 7))
        .thenReturn(Optional.of(PullRequestRecord.builder().runId("run-1").build()));
    when(inboxRepo.findByRepoFullNameAndPrNumberAndCommentId("acme/widget", 7, 99L))
        .thenReturn(Optional.empty());
    when(inboxRepo.save(any(PrCommentInbox.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void ingestBoundsAnOversizedCommentBody() { // RR-193
    trackedPr();
    String oversized = "x".repeat(PrCommentInboxService.MAX_BODY_CHARS + 5_000);

    Optional<PrCommentInbox> saved = service.ingest(ingestRequest(oversized));

    assertThat(saved).isPresent();
    assertThat(saved.get().getBody().length()).isEqualTo(PrCommentInboxService.MAX_BODY_CHARS);
  }

  @Test
  void ssEmitFailureDoesNotBreakDurableIngest() { // RR-194
    trackedPr();
    doThrow(new RuntimeException("sse down")).when(broadcaster).emit(anyString(), any());

    // With no active transaction the emit runs inline; a broadcaster failure must be swallowed so
    // the
    // durable inbox row is still persisted and returned rather than rolled back.
    Optional<PrCommentInbox> saved = service.ingest(ingestRequest("a comment"));

    assertThat(saved).isPresent();
    verify(inboxRepo).save(any(PrCommentInbox.class));
  }

  @Test
  void dismissForRunDoesNotMutateCommentFromAnotherRun() {
    PrCommentInbox row =
        PrCommentInbox.builder().id(11L).runId("run-owner").status("PENDING").build();
    when(inboxRepo.findById(11L)).thenReturn(Optional.of(row));

    Optional<PrCommentInbox> result = service.dismissForRun(11L, "run-attacker-path");

    assertThat(result).isEmpty();
    assertThat(row.getStatus()).isEqualTo("PENDING");
    verify(inboxRepo, never()).save(row);
  }

  // RR-195 — the apply path atomically claims PENDING -> APPLY_QUEUED before enqueuing, so repeated
  // requests cannot each dispatch a duplicate workspace mutation.
  @Test
  void claimForApplySucceedsForExactlyOneWinner() {
    when(inboxRepo.claimForApply(
            org.mockito.ArgumentMatchers.eq(20L),
            org.mockito.ArgumentMatchers.eq("run-1"),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(1);

    assertThat(service.claimForApply(20L, "run-1")).isTrue();
  }

  @Test
  void claimForApplyFailsWhenAlreadyClaimedOrNotPending() {
    when(inboxRepo.claimForApply(
            org.mockito.ArgumentMatchers.eq(21L),
            org.mockito.ArgumentMatchers.eq("run-1"),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(0);

    assertThat(service.claimForApply(21L, "run-1")).isFalse();
  }

  @Test
  void releaseApplyClaimRevertsClaim() {
    service.releaseApplyClaim(22L, "run-1");

    verify(inboxRepo)
        .releaseApplyClaim(
            org.mockito.ArgumentMatchers.eq(22L),
            org.mockito.ArgumentMatchers.eq("run-1"),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void dismissForRunDismissesPendingCommentOwnedByRun() {
    PrCommentInbox row = PrCommentInbox.builder().id(12L).runId("run-1").status("PENDING").build();
    when(inboxRepo.findById(12L)).thenReturn(Optional.of(row));
    when(inboxRepo.save(row)).thenReturn(row);

    Optional<PrCommentInbox> result = service.dismissForRun(12L, "run-1");

    assertThat(result).contains(row);
    assertThat(row.getStatus()).isEqualTo("DISMISSED");
    assertThat(row.getUpdatedAt()).isNotNull();
    verify(inboxRepo).save(row);
  }
}
