package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;

class RunCostEvidenceServiceTest {

  @Test
  void exposesCompleteCallAttributionCoherenceAndHonestRemainder() {
    RunStepUsageRepository repository = mock(RunStepUsageRepository.class);
    RunCostEvidenceService service = new RunCostEvidenceService(repository);
    PipelineRun run = new PipelineRun();
    run.setRunId("r1");
    run.setCoherenceCostUsd(0.04);
    RunStepUsage call =
        RunStepUsage.builder()
            .id("c1")
            .runId("r1")
            .taskId("t1")
            .agentRole("worker")
            .stepId("worker:t1:1")
            .retryOf("worker:t1:0")
            .provider("deepseek")
            .model("deepseek-chat")
            .inputTokens(100)
            .outputTokens(20)
            .cacheReadTokens(30)
            .cacheWriteTokens(5)
            .reasoningTokens(8)
            .costUsd(0.40)
            .callIndex(2)
            .createdAt(Instant.parse("2026-07-17T00:00:00Z"))
            .build();
    when(repository.findByRunIdOrderByCreatedAtAsc("r1")).thenReturn(List.of(call));
    Map<String, Object> body = new LinkedHashMap<>(Map.of("costUsd", 0.50));

    service.enrich(body, run);

    assertThat(body.get("phases"))
        .isInstanceOfSatisfying(
            Map.class, phases -> assertThat(phases.get("coherence")).isEqualTo(0.04));
    assertThat((List<?>) body.get("stepCalls"))
        .singleElement()
        .asString()
        .contains("deepseek-chat");
    assertThat(body).containsEntry("attributedCostUsd", 0.40).containsEntry("reconciled", false);
    assertThat((double) body.get("unattributedCostUsd")).isCloseTo(0.10, within(0.000001));
  }

  private static org.assertj.core.data.Offset<Double> within(double value) {
    return org.assertj.core.data.Offset.offset(value);
  }
}
