package com.navneet.executespec.engine;

import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

import lombok.extern.slf4j.Slf4j;

@Slf4j
final class WorkerCompletionPolicy {

  private boolean awaitingCompletion;
  private boolean wroteMaintainedTest;

  private WorkerCompletionPolicy() {}

  static WorkerCompletionPolicy create() {
    return new WorkerCompletionPolicy();
  }

  static int maxIterations(WorkerTaskRequest request, int defaultMax) {
    return request.maxIterations() != null && request.maxIterations() > 0
        ? request.maxIterations()
        : defaultMax;
  }

  void recordWrite(String path) {
    wroteMaintainedTest |= looksLikeMaintainedTestPath(path);
  }

  boolean shouldComplete(int totalWriteCalls, List<AssistantMessage.ToolCall> calls) {
    return awaitingCompletion
        && totalWriteCalls > 0
        && wroteMaintainedTest
        && allRedundantPostGreenCalls(calls);
  }

  void beforeSubstantiveTurn() {
    awaitingCompletion = false;
  }

  void afterToolBatch(int successfulEdits, boolean fullSuitePassed, int totalWriteCalls) {
    if (successfulEdits > 0) awaitingCompletion = false;
    else if (fullSuitePassed && totalWriteCalls > 0) awaitingCompletion = true;
  }

  void observeAutoVerify(String observation) {
    awaitingCompletion = isPassingAutoVerifyObservation(observation);
  }

  static void appendTextTurn(List<Message> history, String text) {
    if (text != null && !text.isBlank()) history.add(new AssistantMessage(text));
  }

  static String passingAutoVerifyObservation(
      String command, int editsInBurst, int passed, int total) {
    return "AUTO-VERIFY — the platform ran `"
        + command
        + "` after your last "
        + editsInBurst
        + " edit(s): "
        + passed
        + "/"
        + total
        + " passed (exit 0). Tests are green. If the task's requested files and behavior are"
        + " delivered, call completeTask immediately. Do not add another final-verification,"
        + " cleanup, warning-polish, or speculative refactor round-trip.";
  }

  static boolean isPassingAutoVerifyObservation(String observation) {
    return observation != null
        && observation.startsWith("AUTO-VERIFY —")
        && observation.contains(" passed (exit 0). Tests are green.");
  }

  static boolean allRedundantPostGreenCalls(List<AssistantMessage.ToolCall> calls) {
    if (calls == null || calls.isEmpty()) return false;
    return calls.stream()
        .allMatch(
            call ->
                switch (call.name() == null ? "" : call.name()) {
                  case "readFile",
                          "read_file",
                          "searchFiles",
                          "search_files",
                          "listFiles",
                          "list_files",
                          "glob",
                          "gitStatus",
                          "git_status",
                          "gitDiff",
                          "git_diff" ->
                      true;
                  case "buildProject", "build_project", "runTests", "run_tests" -> true;
                  case "shell" -> looksLikeRedundantPostGreenShell(call.arguments());
                  default -> false;
                });
  }

  private static boolean looksLikeRedundantPostGreenShell(String arguments) {
    try {
      String command =
          new ObjectMapper()
              .readTree(arguments == null ? "{}" : arguments)
              .path("command")
              .asText("")
              .strip()
              .toLowerCase(Locale.ROOT);
      return command.matches(
              "(?s)(?:python(?:3)?\\s+-m\\s+)?(?:pytest|black|ruff|isort)(?:\\s+.*)?")
          || command.matches("(?s)(?:npx\\s+)?prettier(?:\\s+.*)?")
          || command.matches(
              "(?s)(?:npm|pnpm|yarn|bun)\\s+(?:run\\s+)?(?:test|lint|format)(?:\\s+.*)?")
          || command.matches("(?s)(?:mvnw?|gradlew?|cargo|go)\\s+(?:test|check|verify)(?:\\s+.*)?");
    } catch (Exception e) {
      return false;
    }
  }

  static boolean looksLikeMaintainedTestPath(String path) {
    if (path == null || path.isBlank()) return false;
    String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
    String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
    return normalized.startsWith("tests/")
        || normalized.contains("/tests/")
        || normalized.startsWith("test/")
        || normalized.contains("/test/")
        || normalized.contains("/src/test/")
        || fileName.startsWith("test_")
        || fileName.endsWith("_test.py")
        || fileName.contains(".test.")
        || fileName.contains(".spec.");
  }

  static boolean isFullSuiteRunTestsCall(ObjectMapper mapper, String arguments) {
    try {
      JsonNode args = mapper.readTree(arguments == null ? "{}" : arguments);
      JsonNode tests = args.has("tests") ? args.path("tests") : args.path("test_ids");
      return tests.isMissingNode() || tests.isNull() || (tests.isArray() && tests.isEmpty());
    } catch (Exception e) {
      return false;
    }
  }

  static WorkerResult greenAutoComplete(
      String runId,
      String taskId,
      int iteration,
      int toolsDispatched,
      int repeatedAssistantTextCount,
      String assistantText,
      List<AssistantMessage.ToolCall> calls,
      List<String> toolLog,
      long startedAt) {
    toolLog.add("AUTO_VERIFY_GREEN → FORCE_COMPLETED_READ_ONLY_LOOP");
    log.info(
        "WorkerAgent green auto-verify force-complete run={} task={} iter={} calls={}",
        runId,
        taskId,
        iteration,
        calls.stream().map(AssistantMessage.ToolCall::name).toList());
    return WorkerResult.builder()
        .outcome(WorkerResult.Outcome.COMPLETED)
        .iterations(iteration)
        .toolCallsDispatched(toolsDispatched)
        .completionSummary(
            "Auto-completed after deterministic tests passed; the model's next turn requested"
                + " only redundant read-only inspection.")
        .finalAssistantText(assistantText)
        .repeatedAssistantTextCount(repeatedAssistantTextCount)
        .toolCallLog(toolLog)
        .totalLatencyMs(System.currentTimeMillis() - startedAt)
        .build();
  }
}
