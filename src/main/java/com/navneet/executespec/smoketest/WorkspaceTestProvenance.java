package com.navneet.executespec.smoketest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

/**
 * Attempt-scoping helpers for the BUILD-mode workspace test runner (RR-283, RR-284).
 *
 * <p>This is the {@code smoketest} sibling of the {@code testgen} provenance helper landed for
 * RR-1582, and it exists for the same reason: JUnit/Surefire report XML on disk carries no attempt
 * identity. A command that fails <i>before</i> writing any report leaves the previous attempt's
 * green XML in place, and the recovery parser would happily score it as this attempt's evidence.
 *
 * <p>Freshness is therefore established by construction — reset before, admit only what postdates
 * the launch — never inferred from whatever files happen to be present.
 */
@Slf4j
final class WorkspaceTestProvenance {

  /**
   * Filesystem timestamp granularity plus container clock skew. Reports written in the first
   * moments of the attempt must not be rejected for being marginally older than the launch instant.
   */
  static final long FRESHNESS_SLACK_SECONDS = 2;

  /** Bounded walk depth, mirroring the recovery parser. */
  private static final int WALK_DEPTH = 8;

  /** Bounded file count, mirroring the recovery parser. */
  private static final int MAX_REPORTS = 2000;

  private WorkspaceTestProvenance() {}

  /** Directory names whose contents are machine-readable test reports for a single attempt. */
  private static boolean isReportDirName(String name) {
    return "surefire-reports".equals(name) || "test-results".equals(name);
  }

  /**
   * Recursively delete every JUnit/Gradle report directory under {@code root} so nothing from an
   * earlier attempt can survive into this one.
   *
   * @return false when a report directory still exists afterwards — fail closed, do not trust any
   *     report-derived count from this attempt
   */
  static boolean clearReports(Path root) {
    List<Path> reportDirs = new ArrayList<>();
    try (var walk = Files.walk(root, WALK_DEPTH)) {
      walk.filter(Files::isDirectory)
          .filter(p -> isReportDirName(p.getFileName().toString()))
          .forEach(reportDirs::add);
    } catch (IOException | RuntimeException e) {
      log.warn("WorkspaceTestRunner could not enumerate report directories under {}", root);
      return false;
    }
    for (Path dir : reportDirs) {
      if (!deleteRecursively(dir)) return false;
    }
    return true;
  }

  private static boolean deleteRecursively(Path dir) {
    if (!Files.exists(dir)) return true;
    try (var walk = Files.walk(dir)) {
      for (Path victim : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(victim);
      }
    } catch (IOException | RuntimeException e) {
      log.warn("WorkspaceTestRunner failed to reset {}: {}", dir, e.getMessage());
      return false;
    }
    return !Files.exists(dir);
  }

  /**
   * Collect the JUnit XML reports under {@code root} that provably postdate {@code launch}.
   *
   * <p>A report older than the launch belongs to a different attempt and is discarded rather than
   * summed. A report whose modification time cannot be read is also discarded: an unreadable
   * timestamp is not proof of freshness.
   */
  static Admission admitFreshReports(Path root, Instant launch) {
    Instant floor = launch.minusSeconds(FRESHNESS_SLACK_SECONDS);
    List<String> fresh = new ArrayList<>();
    boolean[] sawStale = {false};
    try (var walk = Files.walk(root, WALK_DEPTH)) {
      walk.filter(WorkspaceTestProvenance::isReportFile)
          .limit(MAX_REPORTS)
          .forEach(
              p -> {
                try {
                  if (Files.getLastModifiedTime(p).toInstant().isBefore(floor)) {
                    sawStale[0] = true;
                    return;
                  }
                  fresh.add(Files.readString(p));
                } catch (IOException ignored) {
                  // An unreadable report is not evidence; skip it rather than fail the recovery.
                  sawStale[0] = true;
                }
              });
    } catch (IOException | RuntimeException e) {
      return new Admission(List.of(), false);
    }
    return new Admission(List.copyOf(fresh), sawStale[0]);
  }

  private static boolean isReportFile(Path p) {
    String s = p.toString();
    return p.getFileName().toString().startsWith("TEST-")
        && s.endsWith(".xml")
        && (s.contains("surefire-reports") || s.contains("test-results"));
  }

  /**
   * RR-283 — is a parsed summary consistent with the runner's own exit status?
   *
   * <p>The process exit code is the framework's verdict and outranks the summary line. A suite can
   * print "all passed" and still exit non-zero because of a coverage threshold, a plugin or
   * compilation error, a package-level failure, or a teardown error — none of which appear in the
   * per-test counts. Publishing that as green deterministic evidence is the false-positive this
   * scorer exists to prevent.
   *
   * <p>The converse (exit zero with parsed failures) is kept as trustworthy on purpose: it is the
   * pessimistic reading, and discarding it would erase real failure evidence.
   *
   * @return false when the counts contradict the exit status and must not be published
   */
  static boolean countsExplainExitStatus(int exitCode, int failed, int errors) {
    if (exitCode == 0) return true;
    return failed > 0 || errors > 0;
  }

  /**
   * @param reports contents of the report files that provably belong to this attempt
   * @param staleReportsPresent true when at least one report was rejected as not belonging to this
   *     attempt — useful for diagnosing why a recovery produced fewer tests than expected
   */
  record Admission(List<String> reports, boolean staleReportsPresent) {}
}
