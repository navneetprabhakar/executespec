package com.navneet.executespec.agentsession;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnResponse;
import com.navneet.executespec.agentsession.entity.AgentSession;

import org.springframework.stereotype.Component;

@Component
class AgentTracePayloadFactory {

  Map<String, Object> build(
      OrchestratorTurnResponse response,
      AgentSession session,
      String surface,
      String clientVersion,
      String message,
      boolean calledRunApi,
      String createdRunId,
      String deterministicRule) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("response", response);
    payload.put("traceId", response.traceId());
    payload.put("sessionId", session.getId());
    payload.put("turnId", response.turnId());
    payload.put("userId", session.getUserId());
    payload.put("projectId", session.getProjectId());
    payload.put("runId", session.getRunId());
    payload.put("surface", AgentText.firstNonBlank(surface, session.getClientSurface()));
    payload.put("clientVersion", AgentText.firstNonBlank(clientVersion, ""));
    payload.put("messageHash", sha256(AgentText.firstNonBlank(message, "")));
    payload.put("route", response.route());
    payload.put("confidence", response.confidence());
    payload.put("reasonCode", response.reasonCode());
    payload.put("runApiAllowed", response.policy().runApiAllowed());
    payload.put("calledRunApi", calledRunApi);
    payload.put("createdRunId", createdRunId);
    payload.put("modelProvider", "deterministic");
    payload.put("model", "none");
    payload.put("promptId", "orchestrator-deterministic-pre-router");
    payload.put("promptVersion", OrchestratorRoutingPromptCatalog.PROMPT_VERSION);
    payload.put(
        "deterministicRule", AgentText.firstNonBlank(deterministicRule, response.reasonCode()));
    payload.put(
        "policyDecisions",
        List.of(
            Map.of(
                "name",
                response.policy().runApiAllowed()
                    ? "run_allowed_by_route_policy"
                    : "forbid_run_for_non_execution_route",
                "result",
                response.policy().runApiAllowed() ? "allow_confirmed_run" : "allow_non_run",
                "reason",
                response.policy().reason())));
    payload.put("createdAt", Instant.now().toString());
    return payload;
  }

  private static String sha256(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(
                  (value == null ? "" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception ignored) {
      return "";
    }
  }
}
