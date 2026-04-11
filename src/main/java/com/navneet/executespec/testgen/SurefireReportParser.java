package com.navneet.executespec.testgen;

import com.navneet.executespec.testgen.model.TestResult;
import com.navneet.executespec.testgen.model.TestResultStatus;
import com.navneet.executespec.testgen.model.TestTask;
import com.navneet.executespec.testgen.repository.TestTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Parses Maven Surefire XML reports into TestResult entities.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SurefireReportParser {

    private final TestTaskRepository testTaskRepository;

    /**
     * Parse all TEST-*.xml files in the surefire-reports directory.
     */
    public List<TestResult> parse(Path surefireReportsDir, Long testPlanId) {
        List<TestResult> results = new ArrayList<>();

        if (!Files.isDirectory(surefireReportsDir)) {
            log.warn("[SurefireParser] Reports directory not found: {}", surefireReportsDir);
            return results;
        }

        // Build task name → id map for linking results to tasks
        Map<String, Long> taskNameToId = testTaskRepository.findByTestPlanId(testPlanId).stream()
                .collect(Collectors.toMap(TestTask::getTaskName, TestTask::getId, (a, b) -> a));

        try (Stream<Path> files = Files.list(surefireReportsDir)) {
            List<Path> reportFiles = files
                    .filter(p -> p.getFileName().toString().startsWith("TEST-") && p.toString().endsWith(".xml"))
                    .toList();

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
                        String simpleClassName = className.contains(".")
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
                        } catch (NumberFormatException ignored) {}

                        results.add(TestResult.builder()
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
            log.error("[SurefireParser] Failed to list report files in {}: {}", surefireReportsDir, e.getMessage());
        }

        log.info("[SurefireParser] Parsed {} test results from {}", results.size(), surefireReportsDir);
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
