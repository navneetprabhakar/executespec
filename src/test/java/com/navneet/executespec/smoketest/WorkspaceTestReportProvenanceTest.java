package com.navneet.executespec.smoketest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RR-283 + RR-284 — the two ways a workspace test run can manufacture evidence for a run that did
 * not happen: trusting a parsed summary over the runner's own exit status, and scoring report XML
 * left behind by an earlier attempt.
 */
class WorkspaceTestReportProvenanceTest {

  // ---- RR-283: the exit code outranks the parsed summary -------------------------------------

  @Test
  void exitZeroWithNoFailuresIsTrustworthy() {
    assertThat(WorkspaceTestProvenance.countsExplainExitStatus(0, 0, 0)).isTrue();
  }

  @Test
  void nonZeroExitWithParsedFailuresIsTrustworthy() {
    // The suite explains its own failing status — this is real red evidence and must be kept.
    assertThat(WorkspaceTestProvenance.countsExplainExitStatus(1, 2, 0)).isTrue();
    assertThat(WorkspaceTestProvenance.countsExplainExitStatus(1, 0, 3)).isTrue();
  }

  @Test
  void nonZeroExitWithZeroParsedFailuresIsNotTrustworthy() {
    // Coverage threshold, plugin/compilation error, package failure, teardown error: every case
    // prints "all passed" and still fails the build. Publishing it as green is the RR-283 defect.
    assertThat(WorkspaceTestProvenance.countsExplainExitStatus(1, 0, 0)).isFalse();
    assertThat(WorkspaceTestProvenance.countsExplainExitStatus(137, 0, 0)).isFalse();
  }

  @Test
  void exitZeroWithParsedFailuresStaysPessimistic() {
    // Contradictory in the other direction; keeping it preserves failure evidence.
    assertThat(WorkspaceTestProvenance.countsExplainExitStatus(0, 1, 0)).isTrue();
  }

  // ---- RR-284: reports must provably belong to this attempt ----------------------------------

  @Test
  void clearReportsRemovesEveryReportDirectory(@TempDir Path workspace) throws Exception {
    Path surefire = workspace.resolve("target/surefire-reports");
    Path gradle = workspace.resolve("mod/build/test-results");
    Files.createDirectories(surefire);
    Files.createDirectories(gradle);
    Files.writeString(surefire.resolve("TEST-Old.xml"), "<testsuite tests=\"9\"/>");
    Files.writeString(gradle.resolve("TEST-Old.xml"), "<testsuite tests=\"9\"/>");

    assertThat(WorkspaceTestProvenance.clearReports(workspace)).isTrue();
    assertThat(Files.exists(surefire)).isFalse();
    assertThat(Files.exists(gradle)).isFalse();
  }

  @Test
  void clearReportsSucceedsWhenThereIsNothingToClear(@TempDir Path workspace) {
    assertThat(WorkspaceTestProvenance.clearReports(workspace)).isTrue();
  }

  @Test
  void staleReportsFromAnEarlierAttemptAreNotAdmitted(@TempDir Path workspace) throws Exception {
    Path reports = workspace.resolve("target/surefire-reports");
    Files.createDirectories(reports);
    Path stale = reports.resolve("TEST-PreviousAttempt.xml");
    Files.writeString(stale, "<testsuite tests=\"10\" failures=\"0\" errors=\"0\"/>");
    // An hour old: unambiguously a different attempt.
    Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minusSeconds(3600)));

    var admission = WorkspaceTestProvenance.admitFreshReports(workspace, Instant.now());

    assertThat(admission.reports()).isEmpty();
    assertThat(admission.staleReportsPresent()).isTrue();
    // And therefore nothing to parse — no manufactured 10/10 for a command that wrote no reports.
    assertThat(TestOutputParser.parseJunitXmlReports(admission.reports())).isEmpty();
  }

  @Test
  void reportsWrittenByThisAttemptAreAdmitted(@TempDir Path workspace) throws Exception {
    Instant launch = Instant.now();
    Path reports = workspace.resolve("target/surefire-reports");
    Files.createDirectories(reports);
    Files.writeString(
        reports.resolve("TEST-ThisAttempt.xml"),
        "<testsuite tests=\"4\" failures=\"1\" errors=\"0\" skipped=\"0\"/>");

    var admission = WorkspaceTestProvenance.admitFreshReports(workspace, launch);

    assertThat(admission.reports()).hasSize(1);
    assertThat(admission.staleReportsPresent()).isFalse();
    var parsed = TestOutputParser.parseJunitXmlReports(admission.reports());
    assertThat(parsed).isPresent();
    assertThat(parsed.get().getTotalTests()).isEqualTo(4);
    assertThat(parsed.get().getFailed()).isEqualTo(1);
  }

  @Test
  void aMixOfFreshAndStaleReportsAdmitsOnlyTheFreshOnes(@TempDir Path workspace) throws Exception {
    Path reports = workspace.resolve("target/surefire-reports");
    Files.createDirectories(reports);
    Path stale = reports.resolve("TEST-Old.xml");
    Files.writeString(stale, "<testsuite tests=\"20\" failures=\"0\" errors=\"0\"/>");
    Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minusSeconds(3600)));
    Files.writeString(
        reports.resolve("TEST-New.xml"), "<testsuite tests=\"2\" failures=\"2\" errors=\"0\"/>");

    var admission = WorkspaceTestProvenance.admitFreshReports(workspace, Instant.now());

    var parsed = TestOutputParser.parseJunitXmlReports(admission.reports());
    assertThat(parsed).isPresent();
    // The stale green suite must not pad the count, and must not dilute the fresh failures.
    assertThat(parsed.get().getTotalTests()).isEqualTo(2);
    assertThat(parsed.get().getFailed()).isEqualTo(2);
    assertThat(admission.staleReportsPresent()).isTrue();
  }
}
