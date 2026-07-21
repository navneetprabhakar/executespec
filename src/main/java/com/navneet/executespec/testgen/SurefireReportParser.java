package com.navneet.executespec.testgen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import com.navneet.executespec.testgen.model.TestResult;
import com.navneet.executespec.testgen.model.TestResultStatus;
import com.navneet.executespec.testgen.model.TestTask;
import com.navneet.executespec.testgen.repository.TestTaskRepository;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Parses Maven Surefire XML reports into TestResult entities. */
@Component
@Slf4j
@RequiredArgsConstructor
public class SurefireReportParser {

  private final TestTaskRepository testTaskRepository;

  /**
   * Admit a reports directory as evidence for one execution attempt (RR-1582).
   *
   * <p>Surefire XML on disk carries no attempt identity, so a report left behind by an earlier
   * attempt is indistinguishable from one this attempt produced. Every report file must therefore
   * postdate the launch instant. A single stale file means the pre-execution reset did not take
   * effect and the whole directory is refused rather than partially trusted.
   *
   * @param surefireReportsDir directory to admit
   * @param freshnessFloor no report file may be older than this instant
   */
  public ReportAdmission admit(Path surefireReportsDir, java.time.Instant freshnessFloor) {
    if (!Files.isDirectory(surefireReportsDir)) {
      return ReportAdmission.refused("surefire report directory is absent");
    }
    List<Path> reportFiles;
    try (Stream<Path> files = Files.list(surefireReportsDir)) {
      reportFiles = files.filter(SurefireReportParser::isReportFile).sorted().toList();
    } catch (Exception e) {
      return ReportAdmission.refused("surefire report directory is unreadable");
    }
    if (reportFiles.isEmpty()) {
      return ReportAdmission.refused("no surefire report was produced");
    }
    for (Path reportFile : reportFiles) {
      java.time.Instant modified;
      try {
        modified = Files.getLastModifiedTime(reportFile).toInstant();
      } catch (Exception e) {
        return ReportAdmission.refused("surefire report timestamp is unreadable");
      }
      if (freshnessFloor != null && modified.isBefore(freshnessFloor)) {
        log.warn(
            "[SurefireParser] Stale report {} (modified {}, floor {}) — refusing directory",
            reportFile.getFileName(),
            modified,
            freshnessFloor);
        return ReportAdmission.refused("surefire report predates this execution attempt");
      }
    }
    return new ReportAdmission(true, null, reportFiles);
  }

  private static boolean isReportFile(Path p) {
    return p.getFileName().toString().startsWith("TEST-") && p.toString().endsWith(".xml");
  }

  /**
   * Outcome of {@link #admit(Path, java.time.Instant)}.
   *
   * @param admitted true only when every report file provably belongs to this attempt
   */
  public record ReportAdmission(boolean admitted, String reason, List<Path> reportFiles) {
    static ReportAdmission refused(String reason) {
      return new ReportAdmission(false, reason, List.of());
    }
  }

  /** Parse all TEST-*.xml files in the surefire-reports directory. */
  public List<TestResult> parse(Path surefireReportsDir, Long testPlanId) {
    if (!Files.isDirectory(surefireReportsDir)) {
      log.warn("[SurefireParser] Reports directory not found: {}", surefireReportsDir);
      return new ArrayList<>();
    }
    try (Stream<Path> files = Files.list(surefireReportsDir)) {
      return parse(files.filter(SurefireReportParser::isReportFile).sorted().toList(), testPlanId);
    } catch (Exception e) {
      log.error(
          "[SurefireParser] Failed to list report files in {}: {}",
          surefireReportsDir,
          e.getMessage());
      return new ArrayList<>();
    }
  }

  /** Parse an explicit, already-admitted set of Surefire report files. */
  public List<TestResult> parse(List<Path> reportFiles, Long testPlanId) {
    List<TestResult> results = new ArrayList<>();
    if (reportFiles == null || reportFiles.isEmpty()) {
      return results;
    }

    // Build task name → id map for linking results to tasks
    Map<String, Long> taskNameToId =
        testTaskRepository.findByTestPlanId(testPlanId).stream()
            .collect(Collectors.toMap(TestTask::getTaskName, TestTask::getId, (a, b) -> a));

    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      // Disable external entities for security
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      DocumentBuilder builder = factory.newDocumentBuilder();

      for (Path reportFile : reportFiles) {
        try {
          Document doc = builder.parse(reportFile.toFile());
          Element testsuite = doc.getDocumentElement();
          NodeList testcases = testsuite.getElementsByTagName("testcase");

          for (int i = 0; i < testcases.getLength(); i++) {
            Element tc = (Element) testcases.item(i);
            String methodName = tc.getAttribute("name");
            String className = tc.getAttribute("classname");
            String timeStr = tc.getAttribute("time");

            // Derive simple class name from FQ classname
            String simpleClassName =
                className.contains(".")
                    ? className.substring(className.lastIndexOf('.') + 1)
                    : className;

            Long testTaskId = taskNameToId.get(simpleClassName);
            if (testTaskId == null) {
              log.debug("[SurefireParser] No matching task for class {}", simpleClassName);
              testTaskId = 0L; // orphan result
            }

            TestResultStatus status = TestResultStatus.PASSED;
            String failureMessage = null;
            String expectedValue = null;
            String actualValue = null;

            // Check for failure/error/skipped child elements
            NodeList failures = tc.getElementsByTagName("failure");
            NodeList errors = tc.getElementsByTagName("error");
            NodeList skipped = tc.getElementsByTagName("skipped");

            if (failures.getLength() > 0) {
              status = TestResultStatus.FAILED;
              Element failure = (Element) failures.item(0);
              failureMessage = failure.getAttribute("message");
              String body = failure.getTextContent();
              // Try to extract expected/actual from assertion message
              if (failureMessage != null && failureMessage.contains("expected")) {
                expectedValue = extractExpected(failureMessage);
                actualValue = extractActual(failureMessage);
              }
            } else if (errors.getLength() > 0) {
              status = TestResultStatus.ERROR;
              Element error = (Element) errors.item(0);
              failureMessage = error.getAttribute("message");
            } else if (skipped.getLength() > 0) {
              status = TestResultStatus.SKIPPED;
            }

            int durationMs = 0;
            try {
              durationMs = (int) (Double.parseDouble(timeStr) * 1000);
            } catch (NumberFormatException ignored) {
            }

            results.add(
                TestResult.builder()
                    .testPlanId(testPlanId)
                    .testTaskId(testTaskId)
                    .testMethod(methodName)
                    .status(status)
                    .failureMessage(failureMessage)
                    .expectedValue(expectedValue)
                    .actualValue(actualValue)
                    .durationMs(durationMs)
                    .build());
          }
        } catch (Exception e) {
          log.warn("[SurefireParser] Failed to parse {}: {}", reportFile, e.getMessage());
        }
      }
    } catch (Exception e) {
      log.error("[SurefireParser] Failed to parse report files: {}", e.getMessage());
    }

    log.info(
        "[SurefireParser] Parsed {} test results from {} report file(s)",
        results.size(),
        reportFiles.size());
    return results;
  }

  private String extractExpected(String message) {
    // Pattern: "expected:<X> but was:<Y>" or "Expected X but got Y"
    int idx = message.indexOf("expected:");
    if (idx >= 0) {
      int end = message.indexOf("> but", idx);
      if (end > idx) return message.substring(idx + 9, end + 1);
    }
    return null;
  }

  private String extractActual(String message) {
    int idx = message.indexOf("but was:");
    if (idx >= 0) {
      return message.substring(idx + 8).replaceAll("[<>]", "").trim();
    }
    return null;
  }
}
