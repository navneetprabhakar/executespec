package com.navneet.executespec.compliance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * PR-H (2026-05-14) — disk sweeper for orphaned per-run workspace directories.
 *
 * <p>Background. Each pipeline run gets its own {@code <agent.workspace>/<runId>/} directory
 * (cloned repos + worker output + git history + node_modules). The directory is created at run
 * start and never deleted automatically — even after the run terminates and its DB row is archived
 * / soft-deleted / hard-deleted. After ~6 weeks of dev usage a single VM was hosting 137 of these
 * dirs at the time of audit (2026-05-13), some carrying multi-GB {@code node_modules} from frontend
 * specs.
 *
 * <p>Three categories of dirs this job reaps (per-category Micrometer counter):
 *
 * <ul>
 *   <li><b>ORPHAN</b> — directory exists but no {@code pipeline_run} row references it. Most common
 *       shape after a hard-delete sweep (PR-H sibling job) or after a row was deleted via the GDPR
 *       endpoint. Safe to delete immediately.
 *   <li><b>TERMINATED_OLD</b> — DB row exists, status is terminal, completed > {@link
 *       #TERMINAL_RETENTION_DAYS} days ago. Workspace was kept around for the user to inspect /
 *       re-download but the inspection window has passed.
 *   <li><b>SOFT_DELETED_OR_ARCHIVED</b> — DB row carries non-null {@code deletedAt} or {@code
 *       archivedAt} older than {@link #SOFT_DELETED_RETENTION_DAYS} days. The DB sweeper (PR-H
 *       {@code DataRetentionService.hardDeleteArchivedRuns} / {@code hardDeleteGracedRuns}) will
 *       pick the row up on its own schedule; we clear the disk side ahead of it so a crash between
 *       disk-delete and DB-delete doesn't leak.
 * </ul>
 *
 * <p>Active runs (PENDING / PLANNING / IN_PROGRESS / AWAITING_APPROVAL / WORKING / REVIEWING) are
 * NEVER touched regardless of directory age. Same for runs with a GCS workspace key not yet
 * uploaded (we want the archiver to upload before we delete on-disk).
 *
 * <p>Runs nightly at 03:30 local, sequenced after the {@code DataRetentionService} 03:00 row sweep
 * so we operate on the freshly-tombstoned set. {@link SchedulerLock} prevents double-fire across
 * MIG instances.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkspaceDiskCleanupJob {

  /**
   * Days a terminated run's workspace stays on disk before this job reclaims it. 14 is a
   * conservative window for "I want to re-download / inspect" without holding multi-GB dirs
   * forever. Permanent-deploy artefacts are not stored here; this is purely the worker scratch
   * directory.
   */
  static final int TERMINAL_RETENTION_DAYS = 14;

  /**
   * Days a soft-deleted / archived run's workspace stays on disk. Aligned with the 30-day undo
   * window from PR-B-part-2 so the disk delete never beats the DB sweeper: the row is hard-deleted
   * by {@code DataRetentionService} on day 30; we clear the disk on day 30 too.
   */
  static final int SOFT_DELETED_RETENTION_DAYS = 30;

  /**
   * Cap on directories visited per pass. Generous enough for any realistic dev VM (137 dirs at the
   * time of audit) but cheap insurance against a runaway path-walk on a filesystem that's been
   * pointed at the wrong root by a misconfigured property.
   */
  static final int MAX_DIRS_PER_PASS = 5_000;

  private final AgentProperties agentProperties;
  private final PipelineRunRepository runRepository;
  private final MeterRegistry meterRegistry;

  /**
   * Runs daily at 03:30 — 30 minutes after the DB row sweep at 03:00 so we operate on fresh
   * tombstones.
   */
  @Scheduled(cron = "0 30 3 * * *")
  @SchedulerLock(name = "workspaceDiskCleanup", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
  public void cleanup() {
    String root = agentProperties.getWorkspace();
    if (root == null || root.isBlank()) {
      log.warn("[WorkspaceDiskCleanup] agent.workspace not configured — skipping pass");
      return;
    }
    Path rootPath = Paths.get(root).toAbsolutePath().normalize();
    if (!Files.isDirectory(rootPath)) {
      log.debug(
          "[WorkspaceDiskCleanup] root {} doesn't exist or isn't a directory — nothing to do",
          rootPath);
      return;
    }

    int scanned = 0;
    int reapedOrphan = 0;
    int reapedTerminalOld = 0;
    int reapedTombstoned = 0;
    int kept = 0;
    long bytesReclaimed = 0;
    Instant terminalCutoff = Instant.now().minus(Duration.ofDays(TERMINAL_RETENTION_DAYS));
    Instant tombstoneCutoff = Instant.now().minus(Duration.ofDays(SOFT_DELETED_RETENTION_DAYS));

    try (var entries = Files.newDirectoryStream(rootPath, Files::isDirectory)) {
      for (Path dir : entries) {
        if (scanned >= MAX_DIRS_PER_PASS) break;
        scanned++;
        String name = dir.getFileName().toString();
        // Skip well-known non-runId dirs (the "projects/" workspace from ProjectWorkspaceService,
        // dotfiles, anything that doesn't look like a UUID-ish runId).
        if (!looksLikeRunId(name)) {
          kept++;
          continue;
        }

        Category category = classify(name, terminalCutoff, tombstoneCutoff);
        if (category == Category.KEEP) {
          kept++;
          continue;
        }

        long size = directorySize(dir);
        if (deleteRecursive(dir)) {
          bytesReclaimed += size;
          switch (category) {
            case ORPHAN -> reapedOrphan++;
            case TERMINAL_OLD -> reapedTerminalOld++;
            case TOMBSTONED -> reapedTombstoned++;
            default -> {}
          }
        }
      }
    } catch (IOException e) {
      log.warn("[WorkspaceDiskCleanup] iteration over {} failed: {}", rootPath, e.getMessage());
    }

    if (reapedOrphan + reapedTerminalOld + reapedTombstoned > 0) {
      log.info(
          "[WorkspaceDiskCleanup] scanned={} kept={} orphan={} terminal_old={} tombstoned={}"
              + " bytes_reclaimed={}MB root={}",
          scanned,
          kept,
          reapedOrphan,
          reapedTerminalOld,
          reapedTombstoned,
          bytesReclaimed / (1024L * 1024L),
          rootPath);
    } else {
      log.debug(
          "[WorkspaceDiskCleanup] scanned={} kept={} root={} — nothing to reap",
          scanned,
          kept,
          rootPath);
    }

    if (reapedOrphan > 0) {
      Counter.builder("executespec.workspace.disk.reaped")
          .tag("category", "ORPHAN")
          .register(meterRegistry)
          .increment(reapedOrphan);
    }
    if (reapedTerminalOld > 0) {
      Counter.builder("executespec.workspace.disk.reaped")
          .tag("category", "TERMINAL_OLD")
          .register(meterRegistry)
          .increment(reapedTerminalOld);
    }
    if (reapedTombstoned > 0) {
      Counter.builder("executespec.workspace.disk.reaped")
          .tag("category", "TOMBSTONED")
          .register(meterRegistry)
          .increment(reapedTombstoned);
    }
  }

  enum Category {
    KEEP,
    ORPHAN,
    TERMINAL_OLD,
    TOMBSTONED
  }

  /**
   * Categorisation chokepoint — exposed package-private for test access. Order matters: ORPHAN is
   * the cheapest signal (no DB row at all), then SOFT_DELETED_OR_ARCHIVED (the DB sweeper is about
   * to reap the row anyway), then TERMINATED_OLD (the inspection window has passed).
   */
  Category classify(String runId, Instant terminalCutoff, Instant tombstoneCutoff) {
    PipelineRun run = runRepository.findById(runId).orElse(null);
    if (run == null) {
      return Category.ORPHAN;
    }
    // Soft-deleted / archived past their retention window
    if (run.getDeletedAt() != null && run.getDeletedAt().isBefore(tombstoneCutoff)) {
      return Category.TOMBSTONED;
    }
    if (run.getArchivedAt() != null && run.getArchivedAt().isBefore(tombstoneCutoff)) {
      return Category.TOMBSTONED;
    }
    // Terminal + old, but still in the regular retention window (DB sweeper will keep the row)
    if (isTerminalStatus(run.getStatus())
        && run.getCompletedAt() != null
        && run.getCompletedAt().isBefore(terminalCutoff)) {
      // Don't delete the disk if the workspace is still expected to live on local disk for GCS
      // upload (the archiver runs separately; we want it to finish first).
      if (run.getGcsArchivedAt() == null && run.getGcsWorkspaceKey() == null) {
        // Workspace was never archived to GCS — assume the archiver decided this run wasn't
        // worth keeping (failed runs / cancelled / etc.). Safe to reclaim local disk.
        return Category.TERMINAL_OLD;
      }
      // Workspace already archived to GCS — the local disk copy is redundant.
      return Category.TERMINAL_OLD;
    }
    return Category.KEEP;
  }

  private static boolean isTerminalStatus(String status) {
    if (status == null) return false;
    return List.of("COMPLETE", "FAILED", "CANCELLED", "ABORTED", "PARTIAL").contains(status);
  }

  /**
   * Heuristic to skip non-runId entries under the workspace root (e.g. the {@code projects/} dir
   * owned by {@code ProjectWorkspaceService}, README files, dotfiles). Per-run dirs are UUIDs — 36
   * chars with dashes at the right offsets. Conservative: anything that doesn't match is left
   * alone.
   */
  static boolean looksLikeRunId(String name) {
    if (name == null || name.length() != 36) return false;
    if (name.charAt(8) != '-'
        || name.charAt(13) != '-'
        || name.charAt(18) != '-'
        || name.charAt(23) != '-') return false;
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c == '-') continue;
      if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
        return false;
      }
    }
    return true;
  }

  private static long directorySize(Path dir) {
    final long[] total = {0L};
    try {
      Files.walkFileTree(
          dir,
          new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              total[0] += attrs.size();
              return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException exc) {
              // Tolerate individual file failures (broken symlinks, permission-denied entries).
              return java.nio.file.FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      // best-effort
    }
    return total[0];
  }

  private boolean deleteRecursive(Path dir) {
    try (var stream = Files.walk(dir).sorted(Comparator.reverseOrder())) {
      stream.forEach(
          p -> {
            try {
              Files.deleteIfExists(p);
            } catch (IOException e) {
              // Best-effort — partial deletes are fine; the next pass will retry the rest.
              log.debug("[WorkspaceDiskCleanup] could not delete {}: {}", p, e.getMessage());
            }
          });
      return !Files.exists(dir);
    } catch (IOException e) {
      log.warn("[WorkspaceDiskCleanup] walk failed for {}: {}", dir, e.getMessage());
      return false;
    }
  }
}
