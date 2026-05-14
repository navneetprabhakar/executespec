package com.navneet.executespec.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * PR-H (2026-05-14) — pins the classifier that decides whether a workspace dir gets reaped.
 *
 * <p>Three reapable categories — ORPHAN (no DB row), TOMBSTONED (soft-deleted / archived past the
 * 30-day undo window), TERMINAL_OLD (terminal status + completed past the 14-day inspection
 * window). Any active run + any terminal run still inside its window is KEEP.
 *
 * <p>This is the high-leverage test surface: a regression in the classifier could either burn
 * customer data (false ORPHAN) or leak disk (false KEEP). Pure unit tests, no filesystem.
 */
class WorkspaceDiskCleanupJobTest {

  private PipelineRunRepository runRepository;
  private WorkspaceDiskCleanupJob job;
  private Instant terminalCutoff;
  private Instant tombstoneCutoff;

  @BeforeEach
  void setUp() {
    runRepository = mock(PipelineRunRepository.class);
    AgentProperties props = new AgentProperties();
    job = new WorkspaceDiskCleanupJob(props, runRepository, new SimpleMeterRegistry());
    terminalCutoff =
        Instant.now().minus(Duration.ofDays(WorkspaceDiskCleanupJob.TERMINAL_RETENTION_DAYS));
    tombstoneCutoff =
        Instant.now().minus(Duration.ofDays(WorkspaceDiskCleanupJob.SOFT_DELETED_RETENTION_DAYS));
  }

  private PipelineRun run(String status, Instant completedAt) {
    PipelineRun r = new PipelineRun();
    r.setRunId("11111111-1111-1111-1111-111111111111");
    r.setStatus(status);
    r.setCompletedAt(completedAt);
    return r;
  }

  // ── ORPHAN ──────────────────────────────────────────────────────────────

  @Test
  void orphanDir_noDbRow_classifiesOrphan() {
    when(runRepository.findById("11111111-1111-1111-1111-111111111111"))
        .thenReturn(Optional.empty());

    var cat = job.classify("11111111-1111-1111-1111-111111111111", terminalCutoff, tombstoneCutoff);

    assertThat(cat).isEqualTo(WorkspaceDiskCleanupJob.Category.ORPHAN);
  }

  // ── TOMBSTONED ──────────────────────────────────────────────────────────

  @Test
  void softDeleted_pastWindow_classifiesTombstoned() {
    PipelineRun r = run("COMPLETE", Instant.now().minus(Duration.ofDays(60)));
    r.setDeletedAt(Instant.now().minus(Duration.ofDays(35))); // past 30-day window
    when(runRepository.findById(r.getRunId())).thenReturn(Optional.of(r));

    var cat = job.classify(r.getRunId(), terminalCutoff, tombstoneCutoff);

    assertThat(cat).isEqualTo(WorkspaceDiskCleanupJob.Category.TOMBSTONED);
  }

  @Test
  void softDeleted_insideWindow_keeps() {
    PipelineRun r = run("COMPLETE", Instant.now().minus(Duration.ofDays(5)));
    r.setDeletedAt(Instant.now().minus(Duration.ofDays(10))); // still in 30-day window
    // Need to NOT classify as TERMINAL_OLD either — push completion inside terminal window
    r.setCompletedAt(Instant.now().minus(Duration.ofDays(2)));
    when(runRepository.findById(r.getRunId())).thenReturn(Optional.of(r));

    var cat = job.classify(r.getRunId(), terminalCutoff, tombstoneCutoff);

    assertThat(cat).isEqualTo(WorkspaceDiskCleanupJob.Category.KEEP);
  }

  @Test
  void archived_pastWindow_classifiesTombstoned() {
    PipelineRun r = run("COMPLETE", Instant.now().minus(Duration.ofDays(60)));
    r.setArchivedAt(Instant.now().minus(Duration.ofDays(35)));
    when(runRepository.findById(r.getRunId())).thenReturn(Optional.of(r));

    var cat = job.classify(r.getRunId(), terminalCutoff, tombstoneCutoff);

    assertThat(cat).isEqualTo(WorkspaceDiskCleanupJob.Category.TOMBSTONED);
  }

  // ── TERMINAL_OLD ────────────────────────────────────────────────────────

  @Test
  void terminalOld_pastWindow_classifiesTerminalOld() {
    PipelineRun r = run("COMPLETE", Instant.now().minus(Duration.ofDays(20))); // past 14-day cutoff
    when(runRepository.findById(r.getRunId())).thenReturn(Optional.of(r));

    var cat = job.classify(r.getRunId(), terminalCutoff, tombstoneCutoff);

    assertThat(cat).isEqualTo(WorkspaceDiskCleanupJob.Category.TERMINAL_OLD);
  }

  @Test
  void terminalRecent_keeps() {
    PipelineRun r = run("COMPLETE", Instant.now().minus(Duration.ofDays(5))); // inside window
    when(runRepository.findById(r.getRunId())).thenReturn(Optional.of(r));

    var cat = job.classify(r.getRunId(), terminalCutoff, tombstoneCutoff);

    assertThat(cat).isEqualTo(WorkspaceDiskCleanupJob.Category.KEEP);
  }

  @Test
  void failedOld_classifiesTerminalOld() {
    PipelineRun r = run("FAILED", Instant.now().minus(Duration.ofDays(20)));
    when(runRepository.findById(r.getRunId())).thenReturn(Optional.of(r));

    assertThat(job.classify(r.getRunId(), terminalCutoff, tombstoneCutoff))
        .isEqualTo(WorkspaceDiskCleanupJob.Category.TERMINAL_OLD);
  }

  @Test
  void cancelledOld_classifiesTerminalOld() {
    PipelineRun r = run("CANCELLED", Instant.now().minus(Duration.ofDays(20)));
    when(runRepository.findById(r.getRunId())).thenReturn(Optional.of(r));

    assertThat(job.classify(r.getRunId(), terminalCutoff, tombstoneCutoff))
        .isEqualTo(WorkspaceDiskCleanupJob.Category.TERMINAL_OLD);
  }

  // ── KEEP — protective ─────────────────────────────────────────────────

  @Test
  void runningRun_keeps_evenWithOldCreationDate() {
    // A run that's been WORKING for a long time is still active — never touch its disk.
    PipelineRun r = run("WORKING", null); // no completedAt — still in flight
    when(runRepository.findById(r.getRunId())).thenReturn(Optional.of(r));

    assertThat(job.classify(r.getRunId(), terminalCutoff, tombstoneCutoff))
        .isEqualTo(WorkspaceDiskCleanupJob.Category.KEEP);
  }

  @Test
  void awaitingApproval_keeps() {
    PipelineRun r = run("AWAITING_APPROVAL", null);
    when(runRepository.findById(r.getRunId())).thenReturn(Optional.of(r));

    assertThat(job.classify(r.getRunId(), terminalCutoff, tombstoneCutoff))
        .isEqualTo(WorkspaceDiskCleanupJob.Category.KEEP);
  }

  @Test
  void terminal_withNullCompletedAt_keeps() {
    // Defensive: terminal status but completedAt missing (data integrity issue) — keep, don't
    // assume the worst. The DB sweeper will eventually flag the row.
    PipelineRun r = run("COMPLETE", null);
    when(runRepository.findById(r.getRunId())).thenReturn(Optional.of(r));

    assertThat(job.classify(r.getRunId(), terminalCutoff, tombstoneCutoff))
        .isEqualTo(WorkspaceDiskCleanupJob.Category.KEEP);
  }

  // ── looksLikeRunId guard ────────────────────────────────────────────────

  @Test
  void looksLikeRunId_validUuid_true() {
    assertThat(WorkspaceDiskCleanupJob.looksLikeRunId("11111111-2222-3333-4444-555555555555"))
        .isTrue();
  }

  @Test
  void looksLikeRunId_validHexLower_true() {
    assertThat(WorkspaceDiskCleanupJob.looksLikeRunId("abcdef01-2345-6789-abcd-ef0123456789"))
        .isTrue();
  }

  @Test
  void looksLikeRunId_projectsDir_false() {
    assertThat(WorkspaceDiskCleanupJob.looksLikeRunId("projects")).isFalse();
  }

  @Test
  void looksLikeRunId_dotfile_false() {
    assertThat(WorkspaceDiskCleanupJob.looksLikeRunId(".cache")).isFalse();
  }

  @Test
  void looksLikeRunId_wrongLength_false() {
    assertThat(WorkspaceDiskCleanupJob.looksLikeRunId("not-a-uuid")).isFalse();
    assertThat(
            WorkspaceDiskCleanupJob.looksLikeRunId(
                "111111111-2222-3333-4444-555555555555")) // extra char
        .isFalse();
  }

  @Test
  void looksLikeRunId_nonHexChar_false() {
    // 36 chars + dashes in right places but contains 'g' which isn't hex
    assertThat(WorkspaceDiskCleanupJob.looksLikeRunId("g1111111-2222-3333-4444-555555555555"))
        .isFalse();
  }

  @Test
  void looksLikeRunId_null_false() {
    assertThat(WorkspaceDiskCleanupJob.looksLikeRunId(null)).isFalse();
  }
}
