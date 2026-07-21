package com.navneet.executespec.testgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** RR-1582 / RR-1583 — attempt isolation and V277 fingerprint binding. */
class TestExecutionProvenanceTest {

  @Test
  void clearReportsRemovesEverythingFromAnEarlierAttempt(@TempDir Path tmp) throws Exception {
    Path reports = tmp.resolve("target/surefire-reports");
    Files.createDirectories(reports.resolve("nested"));
    Files.writeString(reports.resolve("TEST-OldTest.xml"), "<testsuite/>");
    Files.writeString(reports.resolve("nested/TEST-OlderTest.xml"), "<testsuite/>");

    assertTrue(TestExecutionProvenance.clearReports(reports));
    assertFalse(Files.exists(reports));
  }

  @Test
  void clearReportsIsANoOpWhenNothingIsThere(@TempDir Path tmp) {
    assertTrue(TestExecutionProvenance.clearReports(tmp.resolve("target/surefire-reports")));
  }

  @Test
  void isolateAttemptMovesReportsUnderTheAttemptId(@TempDir Path tmp) throws Exception {
    Path reports = tmp.resolve("target/surefire-reports");
    Files.createDirectories(reports);
    Path report = reports.resolve("TEST-AlphaTest.xml");
    Files.writeString(report, "<testsuite/>");

    List<Path> moved = TestExecutionProvenance.isolateAttempt(tmp, "exec-1", List.of(report));

    assertEquals(1, moved.size());
    assertTrue(moved.get(0).toString().contains("es-attempt-exec-1"));
    assertTrue(Files.exists(moved.get(0)));
    // The original location is empty again, so a later attempt cannot re-read this attempt's XML.
    assertFalse(Files.exists(report));
  }

  @Test
  void matchingFingerprintsBindTheRun() {
    assertNull(TestExecutionProvenance.fingerprintMismatch("abc123", "abc123"));
  }

  @Test
  void aPlanWithNoFingerprintIsUnboundNotMatching() {
    assertNotNull(TestExecutionProvenance.fingerprintMismatch(null, "abc123"));
    assertNotNull(TestExecutionProvenance.fingerprintMismatch("  ", "abc123"));
  }

  @Test
  void anUncomputableCurrentFingerprintIsAMismatch() {
    assertNotNull(TestExecutionProvenance.fingerprintMismatch("abc123", null));
  }

  @Test
  void workspaceDriftIsAMismatch() {
    String reason = TestExecutionProvenance.fingerprintMismatch("abc123", "def456");
    assertNotNull(reason);
    assertTrue(reason.contains("workspace changed after test planning"));
  }

  @Test
  void containerNameIsShellSafe() {
    String name = TestExecutionProvenance.containerName("es-test-exec", "AB/c 1;rm -rf");
    assertTrue(name.matches("es-test-exec-[a-z0-9-]*"), name);
  }
}
