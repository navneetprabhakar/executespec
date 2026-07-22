package com.navneet.executespec.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

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
                if (!restore(entry, "AUTO")) {
                  log.error(
                      "[PromptVersioning] Auto-rollback did NOT complete for {} v{} — live prompt"
                          + " may still hold the degraded content",
                      entry.getPromptFile(),
                      entry.getVersion());
                }
              }
            });
  }

  /** RR-1304 — a rollback either restored proven content, had nothing to restore, or FAILED. */
  public enum RollbackOutcome {
    /** No audit history for the prompt — nothing to roll back to. */
    NO_HISTORY,
    /**
     * The atomic restore or its post-write hash verification failed — the endpoint must not 200.
     */
    RESTORE_FAILED,
    /** The live prompt now provably holds the pre-optimisation content. */
    ROLLED_BACK
  }

  /**
   * Reverts the named prompt to the content stored in its latest audit entry (i.e. the content
   * before the last optimisation was applied).
   *
   * <p>RR-1304 — the previous implementation truncated the live file in place, swallowed any {@code
   * IOException}, and returned {@code true} whenever an audit row merely existed, so a disk /
   * permission / short-write failure was acknowledged as a successful rollback (HTTP 200) while the
   * live prompt could be left truncated. The restore is now atomic (temp file in the same directory
   * + atomic move) and is only reported successful after re-reading the file and proving its
   * content hash matches the intended content.
   *
   * @param promptFileName file name without directory (e.g. {@code "reviewer-v2.txt"})
   * @return the typed rollback outcome
   */
  public RollbackOutcome rollback(String promptFileName) {
    return auditRepository
        .findTopByPromptFileOrderByVersionDesc(promptFileName)
        .map(
            entry ->
                restore(entry, "MANUAL")
                    ? RollbackOutcome.ROLLED_BACK
                    : RollbackOutcome.RESTORE_FAILED)
        .orElse(RollbackOutcome.NO_HISTORY);
  }

  /**
   * Atomically writes {@code entry.getPreviousContent()} to the live prompt path and verifies the
   * on-disk result by re-reading and hashing it. Returns true only when the file provably holds the
   * intended content.
   */
  private boolean restore(PromptAuditEntry entry, String trigger) {
    Path promptPath = resolvePromptPath(entry.getPromptFile());
    String intended = entry.getPreviousContent() == null ? "" : entry.getPreviousContent();
    byte[] intendedBytes = intended.getBytes(StandardCharsets.UTF_8);
    Path tmp = null;
    try {
      Path dir = promptPath.toAbsolutePath().getParent();
      if (dir != null) Files.createDirectories(dir);
      tmp = Files.createTempFile(dir, ".rollback-", ".tmp");
      Files.write(tmp, intendedBytes);
      try {
        Files.move(
            tmp, promptPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException atomicUnsupported) {
        // Fall back to a non-atomic replace when the filesystem cannot do an atomic move; the
        // reread-and-hash proof below still guards against a short/partial write.
        Files.move(tmp, promptPath, StandardCopyOption.REPLACE_EXISTING);
      }
      tmp = null; // moved

      // Reread + hash proof — a truncated or otherwise corrupt write fails here instead of being
      // reported as a successful rollback.
      byte[] onDisk = Files.readAllBytes(promptPath);
      if (!sha256(onDisk).equals(sha256(intendedBytes))) {
        log.error(
            "[PromptVersioning] Rollback verification MISMATCH for {} v{} — on-disk content does"
                + " not match intended content",
            entry.getPromptFile(),
            entry.getVersion());
        return false;
      }

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
      return true;
    } catch (IOException e) {
      log.error(
          "[PromptVersioning] Rollback failed for {} v{}: {}",
          entry.getPromptFile(),
          entry.getVersion(),
          e.getMessage());
      return false;
    } finally {
      if (tmp != null) {
        try {
          Files.deleteIfExists(tmp);
        } catch (IOException ignored) {
          // best-effort cleanup of the temp file
        }
      }
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
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
