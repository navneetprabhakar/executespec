package com.navneet.executespec.testgen;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.FallbackLlmRouter;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.testgen.model.*;
import com.navneet.executespec.testgen.repository.TestPlanRepository;
import com.navneet.executespec.testgen.repository.TestResultRepository;
import com.navneet.executespec.testgen.repository.TestTaskRepository;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds the final test report from execution results and optionally generates fix suggestions for
 * failures using an LLM (FAST/Haiku).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TestReportGenerator {

  private final TestResultRepository testResultRepository;
  private final TestTaskRepository testTaskRepository;
  private final TestPlanRepository testPlanRepository;
  private final FallbackLlmRouter fallbackLlmRouter;
  private final ModelResolver modelResolver;
  private final StateStore stateStore;
  private final ObjectMapper objectMapper;
  private final SseBroadcaster broadcaster;

  @Value("classpath:prompts/test-fix-suggestions.txt")
  private Resource fixSuggestionsPrompt;

  /** Generate the report and emit it via SSE. */
  public void generateAndEmit(
      String runId, Long testPlanId, TestExecutionSummary summary, String previewUrl) {
    List<TestResult> results = testResultRepository.findByTestPlanId(testPlanId);
    List<TestResult> failures =
        results.stream()
            .filter(
                r ->
                    r.getStatus() == TestResultStatus.FAILED
                        || r.getStatus() == TestResultStatus.ERROR)
            .toList();

    // Build task ID → name map
    Map<Long, String> taskIdToName =
        testTaskRepository.findByTestPlanId(testPlanId).stream()
            .collect(Collectors.toMap(TestTask::getId, TestTask::getTaskName, (a, b) -> a));

    // Generate fix suggestions for failures
    List<TestReportDto.FailureSuggestion> suggestions = new ArrayList<>();
    if (!failures.isEmpty()) {
      suggestions = generateFixSuggestions(runId, testPlanId, failures, taskIdToName);
    }

    // Build report DTO
    List<TestReportDto.TestResultDetail> resultDetails =
        results.stream()
            .map(
                r ->
                    TestReportDto.TestResultDetail.builder()
                        .testResultId(r.getId())
                        .testClass(taskIdToName.getOrDefault(r.getTestTaskId(), "Unknown"))
                        .testMethod(r.getTestMethod())
                        .status(r.getStatus().name())
                        .failureMessage(r.getFailureMessage())
                        .expected(r.getExpectedValue())
                        .actual(r.getActualValue())
                        .durationMs(r.getDurationMs() != null ? r.getDurationMs() : 0)
                        .build())
            .toList();

    TestReportDto report =
        TestReportDto.builder()
            .testPlanId(testPlanId)
            .runId(runId)
            .totalTests(summary.getTotalTests())
            .passed(summary.getPassed())
            .failed(summary.getFailed())
            .skipped(summary.getSkipped())
            .errors(summary.getErrors())
            .results(resultDetails)
            .suggestions(suggestions)
            .executionDurationMs(summary.getExecutionDurationMs())
            .previewUrl(previewUrl)
            .build();

    // Emit via SSE
    try {
      String reportJson = objectMapper.writeValueAsString(report);
      broadcaster.emit(
          runId,
          RunEvent.log(
              runId,
              null,
              "[TestReport] "
                  + summary.getPassed()
                  + "/"
                  + summary.getTotalTests()
                  + " tests passed ("
                  + summary.getFailed()
                  + " failed)"));
      // Emit the full report as a special event type
      broadcaster.emit(runId, RunEvent.testReportReady(runId, reportJson));
    } catch (Exception e) {
      log.error("[TestReport] Failed to emit test report for run {}: {}", runId, e.getMessage());
    }

    log.info(
        "[TestReport] Generated test report for run {}: {}/{} passed, {} suggestions",
        runId,
        summary.getPassed(),
        summary.getTotalTests(),
        suggestions.size());
  }

  private List<TestReportDto.FailureSuggestion> generateFixSuggestions(
      String runId, Long testPlanId, List<TestResult> failures, Map<Long, String> taskIdToName) {

    try {
      // Get conventions doc from test plan
      String conventionsDoc =
          testPlanRepository.findById(testPlanId).map(TestPlan::getConventionsDoc).orElse("");

      // Build failures JSON
      List<Map<String, String>> failuresData =
          failures.stream()
              .map(
                  f ->
                      Map.of(
                          "testClass",
                          taskIdToName.getOrDefault(f.getTestTaskId(), "Unknown"),
                          "testMethod",
                          f.getTestMethod(),
                          "status",
                          f.getStatus().name(),
                          "failureMessage",
                          f.getFailureMessage() != null ? f.getFailureMessage() : "",
                          "expected",
                          f.getExpectedValue() != null ? f.getExpectedValue() : "",
                          "actual",
                          f.getActualValue() != null ? f.getActualValue() : ""))
              .toList();
      String failuresJson = objectMapper.writeValueAsString(failuresData);

      // Build prompt
      String promptText =
          loadPrompt()
              .replace("{failures_json}", failuresJson)
              .replace("{conventions_doc}", conventionsDoc);

      // Call LLM via ModelResolver (LIGHTWEIGHT tier for template work)
      LlmProvider aiProvider =
          stateStore.findRun(runId).map(r -> r.getAiProviderEnum()).orElse(LlmProvider.OPENAI);
      ResolvedModel model = modelResolver.resolve("TestReportGenerator", aiProvider);
      Prompt prompt =
          new Prompt(
              List.of(
                  new SystemMessage(promptText), new UserMessage("Generate fix suggestions now.")));
      FallbackLlmRouter.LlmResponse lr =
          fallbackLlmRouter.callWithFallbackTracked(
              model.providerName(),
              model.getModel(),
              prompt,
              runId,
              "test-fix-suggestion",
              FallbackLlmRouter.FallbackMode.IMMEDIATE,
              0,
              false);

      // Accumulate report tokens on TestPlan
      accumulateReportTokens(testPlanId, lr, model);

      String response = lr.response().getResult().getOutput().getText().strip();
      // Strip markdown fences
      if (response.startsWith("```")) {
        response = response.replaceAll("^```[a-z]*\n?", "").replaceAll("\n?```$", "");
      }

      List<FixSuggestionPlan> plans = objectMapper.readValue(response, new TypeReference<>() {});
      Map<String, TestResult> failuresByClassAndMethod =
          failures.stream()
              .collect(
                  Collectors.toMap(
                      f ->
                          failureKey(
                              taskIdToName.getOrDefault(f.getTestTaskId(), "Unknown"),
                              f.getTestMethod()),
                      f -> f,
                      (a, b) -> a));

      return plans.stream()
          .map(
              p -> {
                String testClass = p.getTestClass() != null ? p.getTestClass() : "";
                TestResult matched =
                    failuresByClassAndMethod.get(failureKey(testClass, p.getTestMethod()));
                String resolvedClass =
                    matched != null
                        ? taskIdToName.getOrDefault(matched.getTestTaskId(), testClass)
                        : testClass;
                return TestReportDto.FailureSuggestion.builder()
                    .testClass(resolvedClass)
                    .testMethod(p.getTestMethod())
                    .failureMessage(matched != null ? matched.getFailureMessage() : "")
                    .classification(p.getClassification())
                    .suggestion(p.getSuggestion())
                    .build();
              })
          .toList();

    } catch (Exception e) {
      log.warn(
          "[TestReport] Failed to generate fix suggestions for run {}: {}", runId, e.getMessage());
      return List.of();
    }
  }

  private void accumulateReportTokens(
      Long testPlanId, FallbackLlmRouter.LlmResponse lr, ResolvedModel model) {
    try {
      var usage = lr.response().getMetadata().getUsage();
      int inTokens = usage.getPromptTokens().intValue();
      int outTokens = usage.getCompletionTokens().intValue();
      testPlanRepository
          .findById(testPlanId)
          .ifPresent(
              testPlan -> {
                testPlan.setReportInputTokens(testPlan.getReportInputTokens() + inTokens);
                testPlan.setReportOutputTokens(testPlan.getReportOutputTokens() + outTokens);
                testPlanRepository.save(testPlan);
              });
    } catch (Exception e) {
      log.warn("[TestReport] Failed to extract token usage: {}", e.getMessage());
    }
  }

  private String loadPrompt() {
    try {
      return fixSuggestionsPrompt.getContentAsString(StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new RuntimeException("Failed to load test-fix-suggestions.txt prompt", e);
    }
  }

  static String failureKey(String testClass, String testMethod) {
    return (testClass == null ? "" : testClass) + "#" + (testMethod == null ? "" : testMethod);
  }

  @Data
  private static class FixSuggestionPlan {
    private String testClass;
    private String testMethod;
    private String classification;
    private String suggestion;
  }
}
