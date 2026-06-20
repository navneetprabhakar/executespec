package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorPolicy;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnResponse;
import com.navneet.executespec.agentsession.entity.AgentSession;

import org.junit.jupiter.api.Test;

class AgentTracePayloadFactoryTest {

  private final AgentTracePayloadFactory factory = new AgentTracePayloadFactory();

  @Test
  void buildsDeterministicTracePayloadForAllowedRunRoute() {
    OrchestratorTurnResponse response =
        new OrchestratorTurnResponse(
            "turn-1",
            "CODE_RUN_REQUIRED",
            0.93,
            "explicit_code_mutation_request",
            true,
            "Create a cloud run?",
            null,
            new OrchestratorPolicy(true, true, "Submit only after confirmation."),
            "trace-1");

    var payload =
        factory.build(
            response,
            session(),
            "CLI",
            "0.4.2",
            "implement auth",
            true,
            "run-123",
            "confirmed_run_submission_gate");

    assertThat(payload.get("response")).isEqualTo(response);
    assertThat(payload.get("traceId")).isEqualTo("trace-1");
    assertThat(payload.get("sessionId")).isEqualTo("s1");
    assertThat(payload.get("turnId")).isEqualTo("turn-1");
    assertThat(payload.get("userId")).isEqualTo("u1");
    assertThat(payload.get("projectId")).isEqualTo("p1");
    assertThat(payload.get("runId")).isEqualTo("session-run");
    assertThat(payload.get("surface")).isEqualTo("CLI");
    assertThat(payload.get("clientVersion")).isEqualTo("0.4.2");
    assertThat(payload.get("messageHash"))
        .isEqualTo("a4a3eae988d97eb6a1d620492c5afc47094ba12b04954e4449f992716ae5a8ad");
    assertThat(payload.get("route")).isEqualTo("CODE_RUN_REQUIRED");
    assertThat(payload.get("confidence")).isEqualTo(0.93);
    assertThat(payload.get("reasonCode")).isEqualTo("explicit_code_mutation_request");
    assertThat(payload.get("runApiAllowed")).isEqualTo(true);
    assertThat(payload.get("calledRunApi")).isEqualTo(true);
    assertThat(payload.get("createdRunId")).isEqualTo("run-123");
    assertThat(payload.get("modelProvider")).isEqualTo("deterministic");
    assertThat(payload.get("model")).isEqualTo("none");
    assertThat(payload.get("promptId")).isEqualTo("orchestrator-deterministic-pre-router");
    assertThat(payload.get("promptVersion"))
        .isEqualTo(OrchestratorRoutingPromptCatalog.PROMPT_VERSION);
    assertThat(payload.get("deterministicRule")).isEqualTo("confirmed_run_submission_gate");
    assertThat(payload.get("createdAt")).isNotNull();
    assertThat(String.valueOf(payload.get("policyDecisions")))
        .contains("run_allowed_by_route_policy", "allow_confirmed_run");
  }

  @Test
  void fallsBackToSessionSurfaceAndReasonCodeForBlockedRoute() {
    OrchestratorTurnResponse response =
        new OrchestratorTurnResponse(
            "turn-2",
            "READ_ONLY_CODE_HELP",
            0.88,
            "read_only_code_help",
            false,
            "I will explain without edits.",
            null,
            new OrchestratorPolicy(false, false, "Read-only request."),
            "trace-2");

    var payload = factory.build(response, session(), null, null, "", false, null, null);

    assertThat(payload.get("surface")).isEqualTo("WEB");
    assertThat(payload.get("clientVersion")).isNull();
    assertThat(payload.get("messageHash"))
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    assertThat(payload.get("runApiAllowed")).isEqualTo(false);
    assertThat(payload.get("calledRunApi")).isEqualTo(false);
    assertThat(payload.get("createdRunId")).isNull();
    assertThat(payload.get("deterministicRule")).isEqualTo("read_only_code_help");
    assertThat(String.valueOf(payload.get("policyDecisions")))
        .contains("forbid_run_for_non_execution_route", "allow_non_run");
  }

  private static AgentSession session() {
    return AgentSession.builder()
        .id("s1")
        .projectId("p1")
        .runId("session-run")
        .userId("u1")
        .clientSurface("WEB")
        .build();
  }
}
