package com.navneet.executespec.prreview.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.project.pr.PullRequestRecordRepository;
import com.navneet.executespec.sse.SseBroadcaster;

import org.junit.jupiter.api.Test;

class PrCommentInboxServiceTest {

  private final PrCommentInboxRepository inboxRepo = mock(PrCommentInboxRepository.class);
  private final PrCommentInboxService service =
      new PrCommentInboxService(
          inboxRepo, mock(PullRequestRecordRepository.class), mock(SseBroadcaster.class));

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
