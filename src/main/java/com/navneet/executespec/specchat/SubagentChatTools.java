package com.navneet.executespec.specchat;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.agentsession.SubagentExecutionService;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol;
import com.navneet.executespec.auth.security.AppUserDetails;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Canonical model-facing subagent action for chat/agent sessions.
 *
 * <p>The child is always a bounded, read-only SCOUT or VERIFIER. The service owns persistence,
 * eligibility, budgets, cancellation and model execution; this adapter only validates arguments
 * and exposes the result as a structured tool envelope. {@link ObjectProvider} avoids a bean cycle
 * because the child execution service itself uses {@link SpecChatService} for its bounded turns.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubagentChatTools {

  private final ObjectProvider<SubagentExecutionService> executionService;
  private final ObjectMapper json;

  public ToolCallback bind(AppUserDetails user, String parentSessionId, String parentTurnId) {
    ToolDefinition definition =
        DefaultToolDefinition.builder()
            .name("spawn_subagent")
            .description(
                "Delegate one bounded read-only repository task to a SCOUT or VERIFIER child. "
                    + "Use only when independent evidence gathering or verification will help; "
                    + "the child cannot edit files, run a coding run, or approve a plan.")
            .inputSchema(
                """
                {
                  "type": "object",
                  "properties": {
                    "role": {"type":"string", "enum":["SCOUT","VERIFIER"]},
                    "objective": {"type":"string", "minLength":1, "maxLength":2000},
                    "maxTurns": {"type":"integer", "minimum":1, "maximum":8},
                    "maxCostUsd": {"type":"number", "minimum":0.01, "maximum":2.0}
                  },
                  "required": ["role", "objective"]
                }
                """)
            .build();
    return new SimpleToolCallback(
        definition,
        input -> {
          if (user == null || user.getUser() == null) {
            return error("authenticated user is required");
          }
          if (parentSessionId == null || parentSessionId.isBlank()) {
            return error("parent session is required");
          }
          String objective = text(input, "objective");
          if (objective == null || objective.isBlank()) {
            return error("objective is required");
          }
          SubagentProtocol.Role role;
          try {
            role = SubagentProtocol.Role.valueOf(textOrDefault(input, "role", "SCOUT").toUpperCase());
          } catch (IllegalArgumentException ex) {
            return error("role must be SCOUT or VERIFIER");
          }
          int maxTurns = boundedInt(input, "maxTurns", 4, 1, 8);
          double maxCostUsd = boundedDouble(input, "maxCostUsd", 0.50, 0.01, 2.0);
          SubagentProtocol.SpawnRequest request =
              new SubagentProtocol.SpawnRequest(
                  UUID.randomUUID().toString(),
                  parentSessionId,
                  parentTurnId,
                  role,
                  objective,
                  maxTurns,
                  maxCostUsd);
          try {
            SubagentProtocol.Result result =
                executionService.getObject().execute(request, user, orgId(user));
            ObjectNode envelope = json.createObjectNode();
            envelope.put("ok", true);
            envelope.set("result", json.valueToTree(result));
            return envelope;
          } catch (RuntimeException ex) {
            log.warn("spawn_subagent failed for session {}: {}", parentSessionId, ex.getMessage());
            return error(ex.getMessage() == null ? "subagent execution failed" : ex.getMessage());
          }
        });
  }

  private static String orgId(AppUserDetails user) {
    String orgId = user.getUser().getOrgId();
    if (orgId == null && user.getOrgMember() != null) {
      orgId = String.valueOf(user.getOrgMember().getOrgId());
    }
    return orgId;
  }

  private static String text(JsonNode input, String field) {
    JsonNode value = input == null ? null : input.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static String textOrDefault(JsonNode input, String field, String fallback) {
    String value = text(input, field);
    return value == null || value.isBlank() ? fallback : value;
  }

  private static int boundedInt(JsonNode input, String field, int fallback, int min, int max) {
    JsonNode value = input == null ? null : input.get(field);
    int parsed = value != null && value.canConvertToInt() ? value.asInt() : fallback;
    return Math.max(min, Math.min(max, parsed));
  }

  private static double boundedDouble(
      JsonNode input, String field, double fallback, double min, double max) {
    JsonNode value = input == null ? null : input.get(field);
    double parsed = value != null && value.isNumber() ? value.asDouble() : fallback;
    return Math.max(min, Math.min(max, parsed));
  }

  private static ObjectNode error(String message) {
    ObjectNode node = new ObjectMapper().createObjectNode();
    node.put("ok", false);
    node.put("error", message == null || message.isBlank() ? "subagent request failed" : message);
    return node;
  }

  private final class SimpleToolCallback implements ToolCallback {
    private final ToolDefinition definition;
    private final ToolBody body;

    private SimpleToolCallback(ToolDefinition definition, ToolBody body) {
      this.definition = definition;
      this.body = body;
    }

    @Override
    public ToolDefinition getToolDefinition() {
      return definition;
    }

    @Override
    public String call(String toolInput) {
      try {
        JsonNode input = toolInput == null || toolInput.isBlank() ? json.createObjectNode() : json.readTree(toolInput);
        return body.run(input).toString();
      } catch (Exception ex) {
        return error("invalid subagent tool input").toString();
      }
    }
  }

  @FunctionalInterface
  private interface ToolBody {
    ObjectNode run(JsonNode input);
  }
}
