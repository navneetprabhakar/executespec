package com.navneet.executespec.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.integration.RunLifecycleService;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * RR-090 — GDPR (Article 17) erasure must (a) never tombstone a live V2 run and (b) actually
 * COMPLETE rather than skip live runs and log incompleteness.
 *
 * <p>These tests pin: the terminal-status allowlist (so an unknown/new execution state fails
 * CLOSED); that erasure cancels live runs through the real {@link RunLifecycleService#cancel} path
 * before tombstoning; that a fully-settled user has any prior obligation cleared; and that a user
 * whose runs stay live records a durable, retryable {@link PendingErasureObligation}.
 */
class DataRetentionErasureLifecycleTest {

  private final PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
  private final ProjectRepository projectRepo = mock(ProjectRepository.class);
  private final TierConfigRepository tierRepo = mock(TierConfigRepository.class);
  private final RunLifecycleService runLifecycle = mock(RunLifecycleService.class);
  private final PendingErasureObligationRepository obligationRepo =
      mock(PendingErasureObligationRepository.class);
  private final ErasureObligationStore obligationStore = mock(ErasureObligationStore.class);

  private DataRetentionService service() {
    return new DataRetentionService(
        runRepo,
        projectRepo,
        tierRepo,
        new SimpleMeterRegistry(),
        runLifecycle,
        obligationRepo,
        obligationStore);
  }

  private static PipelineRun run(String id, String status) {
    PipelineRun r = new PipelineRun();
    r.setRunId(id);
    r.setStatus(status);
    return r;
  }

  @Test
  void terminalStatusSet_containsEveryTerminalStatus_andNoLiveOne() {
    assertThat(PipelineRunRepository.TERMINAL_STATUSES)
        .containsExactlyInAnyOrder(
            "COMPLETE", "FAILED", "PARTIAL", "CANCELLED", "ABORTED", "REJECTED");

    // Every live V2 execution status the repository's own owner/heartbeat/gate queries recognise
    // must be ABSENT. This is the exact assertion the old denylist would have failed.
    assertThat(PipelineRunRepository.TERMINAL_STATUSES)
        .doesNotContain(
            "QUEUED", "PLANNING", "AWAITING_APPROVAL", "APPROVAL_RESUMING", "WORKING", "REVIEWING")
        // Paused runs are resumable checkpoints, not finished work.
        .doesNotContain("PAUSED");
  }

  @Test
  void eraseUserData_cancelsLiveRunsThroughTheRealPath_thenTombstonesTerminal() {
    when(runRepo.findNonTerminalByUserId(eq("user-1"), any()))
        .thenReturn(List.of(run("run-a", "WORKING"), run("run-b", "AWAITING_APPROVAL")));
    when(runLifecycle.cancel(anyString())).thenReturn(true);
    // After cancellation the runs are terminal — nothing remains live.
    when(runRepo.countNonTerminalByUserId(eq("user-1"), any())).thenReturn(0L);
    when(obligationStore.softDeleteTerminal("user-1")).thenReturn(2);

    var result = service().eraseUserData("user-1");

    // The real cancellation path is used for each live run — not a status hack.
    verify(runLifecycle).cancel("run-a");
    verify(runLifecycle).cancel("run-b");
    // Terminal rows are tombstoned only after settling.
    verify(obligationStore).softDeleteTerminal("user-1");
    // Fully settled ⇒ any prior obligation is cleared and none recorded.
    verify(obligationStore).clearObligation("user-1");
    verify(obligationStore, never()).recordObligation(anyString(), anyLong(), anyString());

    assertThat(result.runsSoftDeleted()).isEqualTo(2);
    assertThat(result.liveRunsSkipped()).isZero();
  }

  @Test
  void eraseUserData_recordsDurableObligation_whenRunsCannotBeConfirmedTerminal() {
    when(runRepo.findNonTerminalByUserId(eq("user-1"), any()))
        .thenReturn(List.of(run("run-a", "WORKING")));
    when(runLifecycle.cancel(anyString())).thenReturn(true);
    // The run stays live past the settle window (e.g. a pipeline thread that did not unwind).
    when(runRepo.countNonTerminalByUserId(eq("user-1"), any())).thenReturn(1L);
    when(obligationStore.softDeleteTerminal("user-1")).thenReturn(0);

    var result = service().eraseUserData("user-1");

    // Incompleteness is a tracked, retryable state — a durable obligation, not a dropped log line.
    verify(obligationStore).recordObligation(eq("user-1"), eq(1L), anyString());
    verify(obligationStore, never()).clearObligation("user-1");

    assertThat(result.runsSoftDeleted()).isZero();
    assertThat(result.liveRunsSkipped()).isEqualTo(1L);
  }

  @Test
  void eraseUserData_noLiveRuns_tombstonesAndClearsObligation() {
    when(runRepo.findNonTerminalByUserId(eq("user-1"), any())).thenReturn(List.of());
    when(runRepo.countNonTerminalByUserId(eq("user-1"), any())).thenReturn(0L);
    when(obligationStore.softDeleteTerminal("user-1")).thenReturn(3);

    var result = service().eraseUserData("user-1");

    verify(runLifecycle, never()).cancel(anyString());
    verify(obligationStore).clearObligation("user-1");
    assertThat(result.runsSoftDeleted()).isEqualTo(3);
    assertThat(result.liveRunsSkipped()).isZero();
  }

  @Test
  void eraseUserData_passesTerminalAllowlistToTheSoftDelete_andCountsLiveWithSameSet() {
    when(runRepo.findNonTerminalByUserId(eq("user-1"), eq(PipelineRunRepository.TERMINAL_STATUSES)))
        .thenReturn(List.of());
    when(runRepo.countNonTerminalByUserId(
            eq("user-1"), eq(PipelineRunRepository.TERMINAL_STATUSES)))
        .thenReturn(0L);
    when(obligationStore.softDeleteTerminal("user-1")).thenReturn(1);

    service().eraseUserData("user-1");

    verify(runRepo).findNonTerminalByUserId("user-1", PipelineRunRepository.TERMINAL_STATUSES);
    verify(runRepo, times(1))
        .countNonTerminalByUserId("user-1", PipelineRunRepository.TERMINAL_STATUSES);
  }
}
