package com.navneet.executespec.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.navneet.executespec.cost.RunCostTracker;

import org.junit.jupiter.api.Test;

class RunEventContractTest {

  @Test
  void validationFailureCarriesTypedBlockingDiagnostic() {
    RunEvent event =
        RunEvent.validationFailed(
            "run-1",
            null,
            "DETERMINISTIC_HTTP_REQUIREMENT_MISSING",
            "required HTTP route is absent: POST /items",
            "validation");

    assertEquals("VALIDATION_FAILED", event.type());
    assertEquals(
        "DETERMINISTIC_HTTP_REQUIREMENT_MISSING: required HTTP route is absent: POST /items",
        event.payload());
    assertEquals("DETERMINISTIC_HTTP_REQUIREMENT_MISSING", event.data().get("code"));
    assertEquals("required HTTP route is absent: POST /items", event.data().get("reason"));
    assertEquals("validation", event.data().get("phase"));
    assertEquals(true, event.data().get("publicationBlocked"));
  }

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
    assertEquals(100L, event.data().get("inputTokens"));
    assertEquals(25L, event.data().get("outputTokens"));
    assertEquals(40L, event.data().get("cacheReadTokens"));
    assertEquals(7L, event.data().get("cacheWriteTokens"));
    assertEquals(9L, event.data().get("reasoningTokens"));
    assertEquals(3L, event.data().get("calls"));
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
                "recommendedNextStep",
                "No further work is required for this task.",
                "evidence",
                List.of("build passed")));

    assertEquals("GOAL_DECISION", event.type());
    assertEquals("task-1", event.taskId());
    assertEquals("Goal decision: DONE -> STOP_DONE (ok)", event.payload());
    assertEquals(1, event.schemaVersion());
    assertEquals("DONE", event.data().get("state"));
    assertEquals("STOP_DONE", event.data().get("recommendedDecision"));
    assertEquals(
        "No further work is required for this task.", event.data().get("recommendedNextStep"));
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

  @Test
  void initialRoutingDecisionCarriesAuditableRouteEvidence() {
    RunEvent event =
        RunEvent.initialRoutingDecision(
            "run-1",
            "task-1",
            "anthropic",
            "claude-haiku-4-5-20251001",
            "openai",
            "gpt-5.4",
            true,
            true,
            List.of("openai/gpt-5.4", "anthropic/claude-sonnet-4-6"),
            List.of("gemini/gemini-3-flash-preview"),
            List.of("persisted BUG_FIX validation pass rate 80.00% from 10 tasks"));

    assertEquals("INITIAL_ROUTING_DECISION", event.type());
    assertEquals(
        "Initial route: anthropic/claude-haiku-4-5-20251001 -> openai/gpt-5.4", event.payload());
    assertEquals("anthropic", event.data().get("currentProvider"));
    assertEquals("gpt-5.4", event.data().get("selectedModel"));
    assertEquals(true, event.data().get("changed"));
    assertEquals(true, event.data().get("observedQualityEvidenceUsed"));
    assertEquals(
        List.of("openai/gpt-5.4", "anthropic/claude-sonnet-4-6"),
        event.data().get("consideredCandidates"));
    assertEquals(
        List.of("persisted BUG_FIX validation pass rate 80.00% from 10 tasks"),
        event.data().get("reasons"));
  }

  @Test
  void taskWavePlanCarriesTypedAdvisoryEnvelope() {
    RunEvent event =
        RunEvent.taskWavePlan(
            "run-1",
            "Task wave plan: 2 bounded waves",
            Map.of(
                "executionMode",
                "SEQUENTIAL_ADVISORY",
                "maxParallelTasks",
                2,
                "waves",
                List.of(List.of("T1", "T2"), List.of("T3"))));

    assertEquals("TASK_WAVE_PLAN", event.type());
    assertEquals("run-1", event.runId());
    assertNull(event.taskId());
    assertEquals("Task wave plan: 2 bounded waves", event.payload());
    assertEquals(1, event.schemaVersion());
    assertEquals("SEQUENTIAL_ADVISORY", event.data().get("executionMode"));
    assertEquals(2, event.data().get("maxParallelTasks"));
    assertEquals(List.of(List.of("T1", "T2"), List.of("T3")), event.data().get("waves"));
  }

  @Test
  void workspaceGenerationCarriesTypedTransactionalAttemptEvidence() {
    RunEvent event =
        RunEvent.workspaceGeneration(
            "run-1",
            "task-1",
            "BUILD generation verifying",
            Map.of(
                "attemptId", "attempt-1",
                "state", "VERIFYING",
                "generationKind", "BUILD_TASK",
                "taskContractDigest", "contract-digest",
                "provider", "openai",
                "model", "gpt-5.4",
                "candidateGeneration", "candidate-digest"));

    assertEquals("WORKSPACE_GENERATION", event.type());
    assertEquals("run-1", event.runId());
    assertEquals("task-1", event.taskId());
    assertEquals("BUILD generation verifying", event.payload());
    assertEquals(1, event.schemaVersion());
    assertEquals("attempt-1", event.data().get("attemptId"));
    assertEquals("VERIFYING", event.data().get("state"));
    assertEquals("contract-digest", event.data().get("taskContractDigest"));
    assertEquals("candidate-digest", event.data().get("candidateGeneration"));
  }

  @Test
  void continuationActionCarriesTypedRerunEnvelope() {
    RunEvent event =
        RunEvent.continuationAction(
            "run-1",
            "task-1",
            "Continuation action: STEP_UP_MODEL -> rerun task with openai/gpt-5.4 (quality)",
            "STEP_UP_MODEL",
            "openai",
            "gpt-5.4",
            "quality",
            1,
            1);

    assertEquals("CONTINUATION_ACTION", event.type());
    assertEquals(
        "Continuation action: STEP_UP_MODEL -> rerun task with openai/gpt-5.4 (quality)",
        event.payload());
    assertEquals("STEP_UP_MODEL", event.data().get("decision"));
    assertEquals("openai", event.data().get("selectedProvider"));
    assertEquals("gpt-5.4", event.data().get("selectedModel"));
    assertEquals("quality", event.data().get("reason"));
    assertEquals(1, event.data().get("attempt"));
    assertEquals(1, event.data().get("maxAttempts"));
    assertEquals(null, event.data().get("limitName"));
    assertEquals(0, event.data().get("usedLimit"));
    assertEquals(0, event.data().get("maxLimit"));
    assertEquals("EXECUTED", event.data().get("status"));
  }

  @Test
  void continuationActionCanCarryApprovedStatus() {
    RunEvent event =
        RunEvent.continuationAction(
            "run-1",
            "task-1",
            "Continuation action approved: STEP_UP_MODEL -> openai/gpt-5.4",
            "STEP_UP_MODEL",
            "openai",
            "gpt-5.4",
            "user approved blocked continuation",
            1,
            1,
            "userApproval",
            1,
            1,
            "APPROVED");

    assertEquals("CONTINUATION_ACTION", event.type());
    assertEquals("APPROVED", event.data().get("status"));
    assertEquals("userApproval", event.data().get("limitName"));
    assertEquals(1, event.data().get("usedLimit"));
    assertEquals(1, event.data().get("maxLimit"));
  }

  @Test
  void continuationActionSkippedCarriesTypedLimitEnvelope() {
    RunEvent event =
        RunEvent.continuationActionSkipped(
            "run-1",
            "task-1",
            "Continuation action skipped: STEP_UP_MODEL -> openai/gpt-5.4 (limit reached)",
            "STEP_UP_MODEL",
            "openai",
            "gpt-5.4",
            "quality",
            "maxModelStepUps",
            0,
            0);

    assertEquals("CONTINUATION_ACTION_SKIPPED", event.type());
    assertEquals(
        "Continuation action skipped: STEP_UP_MODEL -> openai/gpt-5.4 (limit reached)",
        event.payload());
    assertEquals("STEP_UP_MODEL", event.data().get("decision"));
    assertEquals("openai", event.data().get("selectedProvider"));
    assertEquals("gpt-5.4", event.data().get("selectedModel"));
    assertEquals("quality", event.data().get("reason"));
    assertEquals("maxModelStepUps", event.data().get("limitName"));
    assertEquals(0, event.data().get("usedLimit"));
    assertEquals(0, event.data().get("maxLimit"));
  }
}
