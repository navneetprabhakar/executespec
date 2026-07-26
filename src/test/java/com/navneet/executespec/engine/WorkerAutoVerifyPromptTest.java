package com.navneet.executespec.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

class WorkerAutoVerifyPromptTest {

  @Test
  void greenAutoVerifyDirectsImmediateCompletionInsteadOfMoreWork() {
    String observation =
        WorkerCompletionPolicy.passingAutoVerifyObservation("pytest -q", 3, 13, 13);

    assertThat(observation)
        .contains("13/13 passed (exit 0)")
        .contains("call completeTask immediately")
        .contains("Do not add another final-verification")
        .doesNotContain("Tests are green — continue");

    assertThat(WorkerCompletionPolicy.isPassingAutoVerifyObservation(observation)).isTrue();
  }

  @Test
  void postGreenReadOnlyAndRedundantVerificationAreSafeToTerminateButArbitraryShellIsNot() {
    var reads =
        List.of(
            new AssistantMessage.ToolCall("1", "function", "readFile", "{}"),
            new AssistantMessage.ToolCall("2", "function", "glob", "{}"),
            new AssistantMessage.ToolCall("3", "function", "gitDiff", "{}"));
    var redundantVerification =
        List.of(
            new AssistantMessage.ToolCall("1", "function", "readFile", "{}"),
            new AssistantMessage.ToolCall(
                "2", "function", "shell", "{\"command\":\"python3 -m pytest -q\"}"),
            new AssistantMessage.ToolCall(
                "3", "function", "shell", "{\"command\":\"black src tests\"}"));
    var arbitraryShell =
        List.of(
            new AssistantMessage.ToolCall(
                "1", "function", "shell", "{\"command\":\"python scripts/migrate.py\"}"));

    assertThat(WorkerCompletionPolicy.allRedundantPostGreenCalls(reads)).isTrue();
    assertThat(WorkerCompletionPolicy.allRedundantPostGreenCalls(redundantVerification)).isTrue();
    assertThat(WorkerCompletionPolicy.allRedundantPostGreenCalls(arbitraryShell)).isFalse();
    assertThat(WorkerCompletionPolicy.allRedundantPostGreenCalls(List.of())).isFalse();
  }

  @Test
  void onlyWholeSuiteRunTestsCanBecomeGreenCompletionEvidence() {
    ObjectMapper mapper = new ObjectMapper();

    assertThat(WorkerCompletionPolicy.isFullSuiteRunTestsCall(mapper, "{}")).isTrue();
    assertThat(WorkerCompletionPolicy.isFullSuiteRunTestsCall(mapper, "{\"tests\":[]}")).isTrue();
    assertThat(
            WorkerCompletionPolicy.isFullSuiteRunTestsCall(
                mapper, "{\"tests\":[\"tests/test_api.py::test_happy\"]}"))
        .isFalse();
    assertThat(WorkerCompletionPolicy.isFullSuiteRunTestsCall(mapper, "not-json")).isFalse();
  }

  @Test
  void greenShortcutRequiresARealMaintainedTestPath() {
    assertThat(WorkerCompletionPolicy.looksLikeMaintainedTestPath("tests/test_api.py")).isTrue();
    assertThat(WorkerCompletionPolicy.looksLikeMaintainedTestPath("src/test/java/ApiTest.java"))
        .isTrue();
    assertThat(WorkerCompletionPolicy.looksLikeMaintainedTestPath("src/api.spec.ts")).isTrue();
    assertThat(WorkerCompletionPolicy.looksLikeMaintainedTestPath("app/service.py")).isFalse();
    assertThat(WorkerCompletionPolicy.looksLikeMaintainedTestPath("contest/results.json"))
        .isFalse();
  }
}
