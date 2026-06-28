package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class AgentApiOpenApiCoverageTest {

  @Test
  void openApiDocumentsAgentOrchestratorAndManagedAiRoutes() throws IOException {
    String openApi = Files.readString(Path.of("docs/openapi.yaml"));

    List<String> requiredPaths =
        List.of(
            "/agent/actions:",
            "/agent/v2/capabilities:",
            "/agent/sessions:",
            "/agent/sessions/{sessionId}:",
            "/agent/sessions/{sessionId}/events:",
            "/agent/sessions/{sessionId}/turns:",
            "/agent/sessions/{sessionId}/summary-email:",
            "/agent/sessions/{sessionId}/turns/stream:",
            "/agent/v2/sessions/{sessionId}/actions/plan:",
            "/agent/v2/sessions/{sessionId}/actions/{actionId}/events:",
            "/agent/v2/sessions/{sessionId}/actions/{actionId}/result:",
            "/agent/intent/interpret:",
            "/agent/context/resolve:",
            "/agent/gates:",
            "/agent/gates/{runId}:",
            "/orchestrator/turn:",
            "/orchestrator/turn/confirm:",
            "/admin/eval/orchestrator-routing/run:",
            "/admin/test-managed-ai-email:",
            "/billing/managed-ai/balance:",
            "/billing/managed-ai/events:",
            "/billing/managed-ai/policy:");

    assertThat(openApi).contains(requiredPaths.toArray(String[]::new));
  }

  @Test
  void openApiDocumentsAgentLifecycleSchemas() throws IOException {
    String openApi = Files.readString(Path.of("docs/openapi.yaml"));

    assertThat(openApi)
        .contains(
            "AgentTurnResponse:",
            "CapabilityTool:",
            "ActionPlanRequest:",
            "ActionPlanResponse:",
            "ActionEventRequest:",
            "ActionResultRequest:",
            "ActionRecordResponse:",
            "SessionSummaryEmailRequest:",
            "OrchestratorTurnRequest:",
            "OrchestratorTurnResponse:",
            "ManagedAiBalance:",
            "ManagedAiPolicy:");
  }

  @Test
  void openApiDocumentsStreamingTurnEventAndAttachmentContract() throws IOException {
    String openApi = Files.readString(Path.of("docs/openapi.yaml"));

    assertThat(openApi)
        .contains(
            "Event names: `ack`",
            "`route_decision` ({route, confidence,",
            "`proposal` ({action, proposal_id, summary, spec, requires_confirmation})",
            "attachmentIds:",
            "Optional uploaded attachment ids owned by this agent session");
  }
}
