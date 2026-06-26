package com.navneet.executespec.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.navneet.executespec.cost.RunCostTracker;

import org.junit.jupiter.api.Test;

class RunEventContractTest {

  @Test
  void legacyConstructorKeepsPayloadAndAddsEmptyTypedData() {
    RunEvent event = RunEvent.log("run-1", "task-1", "hello");

    assertEquals("LOG", event.type());
    assertEquals("hello", event.payload());
    assertEquals(1, event.schemaVersion());
    assertTrue(event.data().isEmpty());
  }

  @Test
  void tokenUsageCarriesTypedUsageEnvelopeWithoutChangingPayload() {
    RunCostTracker.Snapshot snapshot = new RunCostTracker.Snapshot(100, 25, 7, 40, 0.0123, 3, 9);

    RunEvent event = RunEvent.tokenUsage("run-1", "task-1", "openai", "gpt-5.4", snapshot);

    assertEquals("TOKEN_USAGE", event.type());
    assertEquals("task-1", event.taskId());
    assertEquals("in=100,out=25,total=125", event.payload());
    assertEquals(100, event.data().get("inputTokens"));
    assertEquals(25, event.data().get("outputTokens"));
    assertEquals(40, event.data().get("cacheReadTokens"));
    assertEquals(7, event.data().get("cacheWriteTokens"));
    assertEquals(9, event.data().get("reasoningTokens"));
    assertEquals(3, event.data().get("calls"));
    assertEquals("openai", event.data().get("provider"));
    assertEquals("gpt-5.4", event.data().get("model"));
  }

  @Test
  void toolCallCarriesTypedRemoteToolFieldsWithoutChangingPayloadFrame() {
    RunEvent event =
        RunEvent.toolCall("run-1", "task-1", "{\"callId\":\"c1\"}", "c1", "readFile", "{}", "read");

    assertEquals("TOOL_CALL", event.type());
    assertEquals("{\"callId\":\"c1\"}", event.payload());
    assertEquals("c1", event.data().get("callId"));
    assertEquals("readFile", event.data().get("toolName"));
    assertEquals("{}", event.data().get("argsJson"));
    assertEquals("read", event.data().get("permissionKind"));
  }

  @Test
  void goalDecisionCarriesTypedDecisionEnvelope() {
    RunEvent event =
        RunEvent.goalDecision(
            "run-1",
            "task-1",
            "Goal decision: DONE -> STOP_DONE (ok)",
            Map.of(
                "state",
                "DONE",
                "recommendedDecision",
                "STOP_DONE",
                "reason",
                "ok",
                "evidence",
                List.of("build passed")));

    assertEquals("GOAL_DECISION", event.type());
    assertEquals("task-1", event.taskId());
    assertEquals("Goal decision: DONE -> STOP_DONE (ok)", event.payload());
    assertEquals(1, event.schemaVersion());
    assertEquals("DONE", event.data().get("state"));
    assertEquals("STOP_DONE", event.data().get("recommendedDecision"));
    assertEquals(List.of("build passed"), event.data().get("evidence"));
  }

  @Test
  void continuationDecisionCarriesTypedSelectedCandidateEnvelope() {
    RunEvent event =
        RunEvent.continuationDecision(
            "run-1",
            "task-1",
            "Continuation decision: STEP_UP_MODEL (quality)",
            Map.of(
                "decision",
                "STEP_UP_MODEL",
                "reasons",
                List.of("quality"),
                "selectedCandidate",
                Map.of("provider", "anthropic", "model", "claude-sonnet-4-6")));

    assertEquals("CONTINUATION_DECISION", event.type());
    assertEquals("Continuation decision: STEP_UP_MODEL (quality)", event.payload());
    assertEquals("STEP_UP_MODEL", event.data().get("decision"));
    assertEquals(List.of("quality"), event.data().get("reasons"));
    @SuppressWarnings("unchecked")
    Map<String, Object> candidate = (Map<String, Object>) event.data().get("selectedCandidate");
    assertEquals("anthropic", candidate.get("provider"));
    assertEquals("claude-sonnet-4-6", candidate.get("model"));
  }
}
