package com.navneet.executespec.testgen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

/**
 * Attempt-scoping helpers shared by the two Surefire-based executors (RR-1582, RR-1583).
 *
 * <p>Surefire report XML on disk carries no attempt identity and the {@code target/} directory is
 * bind-mounted read-write, so it outlives {@code --rm} and can be re-entered by a manual
 * re-execution. Freshness is therefore established by construction — reset before, isolate after —
 * not inferred from whatever files happen to be present.
 */
@Slf4j
final class TestExecutionProvenance {

  private TestExecutionProvenance() {}

  /**
   * Recursively delete the Surefire reports directory so nothing from an earlier attempt survives.
   *
   * @return false when the directory still exists afterwards — fail closed, do not execute
   */
  static boolean clearReports(Path reportsDir) {
    if (!Files.exists(reportsDir)) return true;
    try (var walk = Files.walk(reportsDir)) {
      List<Path> victims = walk.sorted(Comparator.reverseOrder()).toList();
      for (Path victim : victims) {
        Files.deleteIfExists(victim);
      }
    } catch (IOException e) {
      log.warn("[TestExec] Failed to reset {}: {}", reportsDir, e.getMessage());
      return false;
    }
    return !Files.exists(reportsDir);
  }

  /**
   * Move the admitted report files under a per-attempt directory, so a parsed summary can never be
   * attributed to a different attempt and a later attempt cannot re-read these files.
   *
   * @return the moved report paths; the originals when the move could not be performed
   */
  static List<Path> isolateAttempt(
      Path testProjectDir, String executionId, List<Path> reportFiles) {
    Path attemptDir = testProjectDir.resolve("target").resolve("es-attempt-" + executionId);
    try {
      Files.createDirectories(attemptDir);
      List<Path> moved = new ArrayList<>();
      for (Path reportFile : reportFiles) {
        Path target = attemptDir.resolve(reportFile.getFileName().toString());
        Files.move(reportFile, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        moved.add(target);
      }
      return List.copyOf(moved);
    } catch (IOException e) {
      log.warn(
          "[TestExec] Could not isolate reports for execution {}: {}", executionId, e.getMessage());
      return reportFiles;
    }
  }

  /**
   * Compare the fingerprint the test plan was bound to (V277) with the workspace as it stands now.
   *
   * @return null when they provably match, otherwise a reason the binding cannot be honoured. A
   *     null on either side is a mismatch: an unbound plan is unbound, not matching.
   */
  static String fingerprintMismatch(String plannedFingerprint, String currentFingerprint) {
    if (plannedFingerprint == null || plannedFingerprint.isBlank()) {
      return "test plan carries no workspace fingerprint to verify against";
    }
    if (currentFingerprint == null || currentFingerprint.isBlank()) {
      return "workspace fingerprint could not be recomputed at execution time";
    }
    if (!plannedFingerprint.equals(currentFingerprint)) {
      return "workspace changed after test planning — tests do not describe the current diff";
    }
    return null;
  }

  /** Deterministic, shell-safe container name derived from the attempt id. */
  static String containerName(String prefix, String executionId) {
    String sanitized = executionId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "");
    return prefix + "-" + sanitized;
  }

  /**
   * Kill a container the JVM-side {@code docker run} client can no longer reach. Destroying the
   * client process does not stop the daemon-side container, so a timed-out run would otherwise leak
   * its CPU/memory reservation indefinitely (RR-1587).
   */
  static void killContainer(String containerName) {
    try {
      Process kill =
          new ProcessBuilder("docker", "kill", containerName)
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (!kill.waitFor(20, TimeUnit.SECONDS)) {
        kill.destroyForcibly();
      }
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      log.warn("[TestExec] docker kill {} failed: {}", containerName, e.getMessage());
    }
  }
}
