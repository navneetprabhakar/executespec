package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.engine.WorkerResult;

import org.junit.jupiter.api.Test;

class SequentialExecutorFailureDiagnosticSanitizationTest {

  @Test
  void durableFailureDiagnosticOmitsAssistantAndToolPayloads() {
    WorkerResult result =
        WorkerResult.builder()
            .outcome(WorkerResult.Outcome.STOPPED_WITHOUT_COMPLETE)
            .iterations(4)
            .toolCallsDispatched(1)
            .finalAssistantText("assistant-secret-canary")
            .toolCallLog(List.of("shell(sig=0123456789abcdef) -> ERR"))
            .totalLatencyMs(12)
            .build();

    String diagnostic = SequentialExecutor.buildFailureDiagnosticJson(result, 20);

    assertThat(diagnostic)
        .contains("shell(sig=0123456789abcdef) -> ERR")
        .doesNotContain("assistant-secret-canary", "lastAssistantText", "command");
  }
}
