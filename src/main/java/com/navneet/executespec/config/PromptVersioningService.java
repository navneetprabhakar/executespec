package com.navneet.executespec.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import com.navneet.executespec.state.entity.PromptAuditEntry;
import com.navneet.executespec.state.repository.PromptAuditRepository;

import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages prompt file versioning and rollback.
 *
 * <p>Before any optimisation is applied, a backup of the current prompt is saved as {@code
 * <name>.v<N>.txt} alongside the live file. On degradation detection or manual admin request, the
 * backup is restored atomically.
 *
 * <p>Auto-rollback fires when the latest optimisation version has accumulated ≥ 5 runs and its
 * failure rate exceeds {@code 40%}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PromptVersioningService {

  /** Minimum runs tracked before auto-rollback is considered. */
  private static final int MIN_RUNS_FOR_ROLLBACK = 5;

  /** Failure rate threshold that triggers auto-rollback. */
  private static final double ROLLBACK_FAILURE_THRESHOLD = 0.40;

  private final PromptAuditRepository auditRepository;
  private final MeterRegistry meterRegistry;

  // ── Backup ───────────────────────────────────────────────────────────────

  /**
   * Saves a versioned backup of the current prompt file before applying an optimisation. Creates
   * {@code <stem>.v<version>.txt} in the same directory.
   *
   * @param promptPath absolute path to the live prompt file
   * @param version the version number that will be written next (backup = version − 1)
   */
  public void backup(Path promptPath, int version) {
    if (!Files.exists(promptPath)) return;
    try {
      String fileName = promptPath.getFileName().toString();
      // e.g. reviewer-v2.txt → reviewer-v2.v3.txt
      String backupName = backupName(fileName, version);
      Path backupPath = promptPath.resolveSibling(backupName);
      Files.copy(promptPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
      log.debug("[PromptVersioning] Backed up {} → {}", fileName, backupName);
    } catch (IOException e) {
      log.warn(
          "[PromptVersioning] Failed to backup {} before v{}: {}",
          promptPath,
          version,
          e.getMessage());
    }
  }

  // ── Auto-rollback ────────────────────────────────────────────────────────

  /**
   * Called after each run outcome is recorded. Checks if the latest optimisation for any prompt has
   * degraded output beyond the threshold and rolls back if so.
   */
  public void checkAndRollbackIfDegraded() {
    auditRepository
        .findAllLatestPerFile()
        .forEach(
            entry -> {
              if (entry.getRunsAfter() < MIN_RUNS_FOR_ROLLBACK) return;

              double failureRate = (double) entry.getFailuresAfter() / entry.getRunsAfter();
              if (failureRate >= ROLLBACK_FAILURE_THRESHOLD) {
                log.warn(
                    "[PromptVersioning] Auto-rollback triggered for {} v{} — failure rate {:.0%}",
                    entry.getPromptFile(), entry.getVersion(), failureRate);
                rollback(entry, "AUTO");
              }
            });
  }

  // ── Manual rollback ──────────────────────────────────────────────────────

  /**
   * Reverts the named prompt to the content stored in its latest audit entry (i.e. the content
   * before the last optimisation was applied).
   *
   * @param promptFileName file name without directory (e.g. {@code "reviewer-v2.txt"})
   * @return true if rollback was performed, false if no audit history found
   */
  public boolean rollback(String promptFileName) {
    return auditRepository
        .findTopByPromptFileOrderByVersionDesc(promptFileName)
        .map(
            entry -> {
              rollback(entry, "MANUAL");
              return true;
            })
        .orElse(false);
  }

  // ── Internal ─────────────────────────────────────────────────────────────

  private void rollback(PromptAuditEntry entry, String trigger) {
    Path promptPath = resolvePromptPath(entry.getPromptFile());
    try {
      Files.writeString(
          promptPath,
          entry.getPreviousContent(),
          StandardCharsets.UTF_8,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.CREATE);

      Counter.builder("executespec.prompt.optimisation.rolledback")
          .tag("prompt", entry.getPromptFile())
          .tag("trigger", trigger)
          .register(meterRegistry)
          .increment();

      log.info(
          "[PromptVersioning] Rolled back {} from v{} to v{} (trigger={})",
          entry.getPromptFile(),
          entry.getVersion(),
          entry.getVersion() - 1,
          trigger);
    } catch (IOException e) {
      log.error(
          "[PromptVersioning] Rollback failed for {} v{}: {}",
          entry.getPromptFile(),
          entry.getVersion(),
          e.getMessage());
    }
  }

  private static String backupName(String fileName, int version) {
    int dot = fileName.lastIndexOf('.');
    if (dot < 0) return fileName + ".v" + version;
    return fileName.substring(0, dot) + ".v" + version + fileName.substring(dot);
  }

  private Path resolvePromptPath(String promptFile) {
    return Path.of("src/main/resources/prompts", promptFile);
  }
}
