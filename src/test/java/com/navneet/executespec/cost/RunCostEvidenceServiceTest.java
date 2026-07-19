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

  /**
   * C1 Step 5 (G5). Without causality fields a cross-provider failover is indistinguishable from a
   * same-provider retry in {@code /api/runs/{id}/cost}. The assertion is chain-shaped on purpose:
   * it pins that the FAILOVER attempt names its parent, not merely that the fields exist.
   */
  @Test
  @SuppressWarnings("unchecked")
  void stepCallsExposeAttemptCausalitySoFailoverIsDistinguishableFromRetry() {
    RunStepUsageRepository repository = mock(RunStepUsageRepository.class);
    RunCostEvidenceService service = new RunCostEvidenceService(repository);
    PipelineRun run = new PipelineRun();
    run.setRunId("r2");
    RunStepUsage primary =
        RunStepUsage.builder()
            .id("a1")
            .runId("r2")
            .provider("deepseek")
            .model("deepseek-v4-pro-native")
            .modelTier("WORKHORSE")
            .attemptKind("PRIMARY")
            .physicalAttemptId("call-1#1")
            .logicalCallId("call-1")
            .attemptStatus("FAILED")
            .createdAt(Instant.parse("2026-07-19T00:00:00Z"))
            .build();
    RunStepUsage failover =
        RunStepUsage.builder()
            .id("a2")
            .runId("r2")
            .provider("openrouter")
            .model("glm-5.2")
            .modelTier("WORKHORSE")
            .attemptKind("FAILOVER")
            .physicalAttemptId("call-1#2")
            .parentAttemptId("call-1#1")
            .logicalCallId("call-1")
            .attemptStatus("SUCCESS")
            .createdAt(Instant.parse("2026-07-19T00:00:01Z"))
            .build();
    when(repository.findByRunIdOrderByCreatedAtAsc("r2")).thenReturn(List.of(primary, failover));
    Map<String, Object> body = new LinkedHashMap<>(Map.of("costUsd", 0.0));

    service.enrich(body, run);

    List<Map<String, Object>> calls = (List<Map<String, Object>>) body.get("stepCalls");
    assertThat(calls).hasSize(2);
    assertThat(calls.get(0))
        .containsEntry("attemptKind", "PRIMARY")
        .containsEntry("physicalAttemptId", "call-1#1")
        .containsEntry("parentAttemptId", null)
        .containsEntry("modelTier", "WORKHORSE")
        .containsEntry("logicalCallId", "call-1");
    assertThat(calls.get(1))
        .containsEntry("attemptKind", "FAILOVER")
        .containsEntry("physicalAttemptId", "call-1#2")
        .containsEntry("parentAttemptId", "call-1#1")
        .containsEntry("provider", "openrouter");
    // The route changed AND the change is attributable to a distinguishable kind + parent, which
    // is the C1 contract: route changes are recorded, not forbidden.
    assertThat(calls.get(0).get("provider")).isNotEqualTo(calls.get(1).get("provider"));
  }

  private static org.assertj.core.data.Offset<Double> within(double value) {
    return org.assertj.core.data.Offset.offset(value);
  }
}
