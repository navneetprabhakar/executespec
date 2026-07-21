package com.navneet.executespec.testgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.navneet.executespec.testgen.repository.TestTaskRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** RR-1582 — a report that predates this attempt is not this attempt's evidence. */
class SurefireReportFreshnessTest {

  private final SurefireReportParser parser =
      new SurefireReportParser(mock(TestTaskRepository.class));

  private Path writeReport(Path dir, String name, Instant modified) throws Exception {
    Files.createDirectories(dir);
    Path file = dir.resolve(name);
    Files.writeString(file, "<testsuite name=\"x\"></testsuite>");
    Files.setLastModifiedTime(file, FileTime.from(modified));
    return file;
  }

  @Test
  void reportOlderThanTheAttemptIsRefused(@TempDir Path tmp) throws Exception {
    Instant launch = Instant.now();
    writeReport(tmp, "TEST-StaleTest.xml", launch.minus(2, ChronoUnit.HOURS));

    SurefireReportParser.ReportAdmission admission = parser.admit(tmp, launch);

    assertFalse(admission.admitted());
    assertEquals("surefire report predates this execution attempt", admission.reason());
    assertTrue(admission.reportFiles().isEmpty());
  }

  @Test
  void oneStaleReportPoisonsTheWholeDirectory(@TempDir Path tmp) throws Exception {
    Instant launch = Instant.now();
    writeReport(tmp, "TEST-FreshTest.xml", launch.plusSeconds(1));
    writeReport(tmp, "TEST-StaleTest.xml", launch.minus(1, ChronoUnit.HOURS));

    assertFalse(parser.admit(tmp, launch).admitted());
  }

  @Test
  void freshReportsAreAdmitted(@TempDir Path tmp) throws Exception {
    Instant launch = Instant.now();
    writeReport(tmp, "TEST-AlphaTest.xml", launch.plusSeconds(1));
    writeReport(tmp, "TEST-BetaTest.xml", launch.plusSeconds(2));

    SurefireReportParser.ReportAdmission admission = parser.admit(tmp, launch);

    assertTrue(admission.admitted());
    assertEquals(2, admission.reportFiles().size());
  }

  @Test
  void absentDirectoryIsRefused(@TempDir Path tmp) {
    SurefireReportParser.ReportAdmission admission =
        parser.admit(tmp.resolve("surefire-reports"), Instant.now());
    assertFalse(admission.admitted());
    assertEquals("surefire report directory is absent", admission.reason());
  }

  @Test
  void emptyDirectoryIsRefused(@TempDir Path tmp) throws Exception {
    Path dir = Files.createDirectories(tmp.resolve("surefire-reports"));
    SurefireReportParser.ReportAdmission admission = parser.admit(dir, Instant.now());
    assertFalse(admission.admitted());
    assertEquals("no surefire report was produced", admission.reason());
  }

  @Test
  void nonReportFilesAreNotEvidence(@TempDir Path tmp) throws Exception {
    Files.createDirectories(tmp);
    Files.writeString(tmp.resolve("AlphaTest.txt"), "not xml");
    assertFalse(parser.admit(tmp, Instant.now()).admitted());
  }
}
