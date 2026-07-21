package com.navneet.executespec.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * RR-090 — GDPR erasure must never tombstone a live V2 run.
 *
 * <p>The original {@code softDeleteByUserId} was a {@code NOT IN} DENYLIST naming only the obsolete
 * status spellings {@code PENDING} / {@code PLANNING} / {@code IN_PROGRESS} / {@code
 * IN_PROGRESS_BATCH}. The live V2 execution states are {@code QUEUED}, {@code PLANNING}, {@code
 * AWAITING_APPROVAL}, {@code APPROVAL_RESUMING}, {@code WORKING} and {@code REVIEWING}, so four of
 * the six were never protected: erasure could tombstone a currently spending/writing run, and the
 * 7-day retention sweep would then hard-delete the row while the worker was still active.
 *
 * <p>These tests pin the corrected shape: an {@code IN} ALLOWLIST of terminal statuses, so an
 * unknown or newly added execution state fails CLOSED rather than being tombstoned.
 */
class DataRetentionErasureLifecycleTest {

  private final PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
  private final ProjectRepository projectRepo = mock(ProjectRepository.class);
  private final TierConfigRepository tierRepo = mock(TierConfigRepository.class);

  private DataRetentionService service() {
    return new DataRetentionService(runRepo, projectRepo, tierRepo, new SimpleMeterRegistry());
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
  void eraseUserData_passesTerminalAllowlistToTheSoftDelete() {
    when(runRepo.softDeleteTerminalByUserId(anyString(), any(), any())).thenReturn(3);
    when(runRepo.countNonTerminalByUserId(anyString(), any())).thenReturn(0L);

    var result = service().eraseUserData("user-1");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> statuses = ArgumentCaptor.forClass(List.class);
    org.mockito.Mockito.verify(runRepo)
        .softDeleteTerminalByUserId(eq("user-1"), any(Instant.class), statuses.capture());

    assertThat(statuses.getValue()).isEqualTo(PipelineRunRepository.TERMINAL_STATUSES);
    assertThat(result.runsSoftDeleted()).isEqualTo(3);
    assertThat(result.liveRunsSkipped()).isZero();
  }

  @Test
  void eraseUserData_reportsLiveRunsItRefusedToTombstone() {
    when(runRepo.softDeleteTerminalByUserId(anyString(), any(), any())).thenReturn(1);
    when(runRepo.countNonTerminalByUserId(anyString(), any())).thenReturn(2L);

    var result = service().eraseUserData("user-1");

    // Erasure is visibly INCOMPLETE rather than silently partial: the two live runs are refused,
    // not guessed at, and the caller can tell.
    assertThat(result.runsSoftDeleted()).isEqualTo(1);
    assertThat(result.liveRunsSkipped()).isEqualTo(2);
  }
}
