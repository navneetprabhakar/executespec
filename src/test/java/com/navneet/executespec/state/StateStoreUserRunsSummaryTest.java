package com.navneet.executespec.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.state.repository.AgentMessageRepository;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.CompactionEventRepository;
import com.navneet.executespec.state.repository.GateEventRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.state.repository.PlanVersionRepository;
import com.navneet.executespec.state.repository.PromptAuditRepository;
import com.navneet.executespec.state.repository.RunRecoveryRepository;

import org.junit.jupiter.api.Test;

/**
 * RR-079 — the global user-runs summary aggregates per-user counts in SQL and batch-resolves only
 * the run-owners' names, instead of loading every run and every active user into heap.
 */
class StateStoreUserRunsSummaryTest {

  private final PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
  private final AppUserRepository appUserRepo = mock(AppUserRepository.class);

  private StateStore store() {
    return new StateStore(
        runRepo,
        mock(AgentTaskRepository.class),
        mock(AgentMessageRepository.class),
        mock(GateEventRepository.class),
        mock(CompactionEventRepository.class),
        new ObjectMapper(),
        mock(RunRecoveryRepository.class),
        mock(PromptAuditRepository.class),
        appUserRepo,
        mock(PlanVersionRepository.class),
        mock(ModelRegistry.class));
  }

  private static com.navneet.executespec.state.repository.UserRunAggregate agg(
      String uid, long runCount, long completed, long failed, long in, long out) {
    return new com.navneet.executespec.state.repository.UserRunAggregate() {
      @Override
      public String getUid() {
        return uid;
      }

      @Override
      public long getRunCount() {
        return runCount;
      }

      @Override
      public long getCompleted() {
        return completed;
      }

      @Override
      public long getFailed() {
        return failed;
      }

      @Override
      public long getTotalIn() {
        return in;
      }

      @Override
      public long getTotalOut() {
        return out;
      }
    };
  }

  @Test
  void globalSummaryAggregatesInSqlAndSortsByRunCountDescending() {
    when(runRepo.aggregateUserRunStats())
        .thenReturn(List.of(agg("u2", 2, 2, 0, 20, 10), agg("u1", 5, 3, 1, 100, 50)));
    when(appUserRepo.findAllById(any()))
        .thenReturn(
            List.of(
                AppUser.builder()
                    .id("u1")
                    .firstName("Alice")
                    .lastName("A")
                    .status("ACTIVE")
                    .build(),
                AppUser.builder()
                    .id("u2")
                    .firstName("Bob")
                    .lastName("B")
                    .status("ACTIVE")
                    .build()));

    var summary = store().computeUserRunsSummaryGlobal(0);

    // Sorted by run count descending, same as the previous in-Java grouping.
    assertThat(summary.users())
        .extracting(StateStore.UserRunStat::userId)
        .containsExactly("u1", "u2");
    var top = summary.users().get(0);
    assertThat(top.displayName()).isEqualTo("Alice A");
    assertThat(top.runCount()).isEqualTo(5);
    assertThat(top.completedCount()).isEqualTo(3);
    assertThat(top.failedCount()).isEqualTo(1);
    assertThat(top.totalInputTokens()).isEqualTo(100L);
    assertThat(top.totalOutputTokens()).isEqualTo(50L);

    // The unbounded full-scan path is gone.
    verify(runRepo).aggregateUserRunStats();
    verify(runRepo, never()).findAll();
    verify(appUserRepo, never()).findAllActive();
  }

  @Test
  void deletedOrUnknownOwnerFallsBackToTruncatedId() {
    when(runRepo.aggregateUserRunStats())
        .thenReturn(List.of(agg("deleted-user-1234", 1, 0, 0, 0, 0)));
    // Owner exists but is DELETED — must NOT surface their name (matches the old findAllActive
    // rule).
    when(appUserRepo.findAllById(any()))
        .thenReturn(
            List.of(
                AppUser.builder()
                    .id("deleted-user-1234")
                    .firstName("Ghost")
                    .lastName("User")
                    .status("DELETED")
                    .build()));

    var summary = store().computeUserRunsSummaryGlobal(0);

    assertThat(summary.users()).hasSize(1);
    assertThat(summary.users().get(0).displayName()).isEqualTo("deleted-…");
  }
}
