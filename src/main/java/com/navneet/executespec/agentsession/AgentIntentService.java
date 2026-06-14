package com.navneet.executespec.agentsession;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ClientCapabilities;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.IntentDecision;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.InterpretIntentRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TypedAction;

import org.springframework.stereotype.Service;

/**
 * Conservative first-pass intent classifier.
 *
 * <p>It intentionally does not call an LLM yet. This gives CLI/VS Code a deterministic typed-action
 * contract while preserving billing: cost-bearing/destructive actions are proposals that require a
 * separate confirmation step.
 */
@Service
public class AgentIntentService {

  private static final Pattern UUIDISH =
      Pattern.compile(
          "\\b[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}\\b|\\b[0-9a-fA-F]{8,}\\b");

  public IntentDecision interpret(InterpretIntentRequest request) {
    String message = request == null || request.message() == null ? "" : request.message().trim();
    String lower = message.toLowerCase(Locale.ROOT);
    ClientCapabilities caps = request == null ? null : request.clientCapabilities();

    if (message.isBlank()) {
      return chat("Tell me what you want to inspect, plan, approve, or change.", 0.99);
    }

    if (containsAny(lower, "approve", "approved")) {
      String runId = extractRunId(message);
      if (runId == null) {
        return chat(
            "Which run should I approve? I need a specific run before creating an approval"
                + " confirmation.",
            0.86);
      }
      return confirm(
          "APPROVE_GATE",
          0.9,
          "approve_run",
          Map.of("runId", runId),
          "Approve run " + runId + "? This may charge run credits through the existing plan gate.");
    }

    if (containsAny(lower, "reject", "cancel", "decline")) {
      String runId = extractRunId(message);
      if (runId == null) {
        return chat("Which run should I reject? I need a specific run id.", 0.86);
      }
      return confirm(
          "REJECT_GATE", 0.9, "reject_run", Map.of("runId", runId), "Reject run " + runId + "?");
    }

    if (containsAny(lower, "pending approval", "awaiting approval", "approval", "gates")) {
      return action(
          "LIST_GATES",
          0.95,
          new TypedAction("list_gates", Map.of(), false, "none", null),
          "I will list runs awaiting plan approval.");
    }

    if (containsAny(lower, "cost", "spend", "billing")) {
      return action(
          "COST_QUERY",
          0.82,
          new TypedAction("cost_query", Map.of("message", message), false, "none", null),
          "I can answer cost questions from run/session ledgers when the client asks for details.");
    }

    if (looksLikeCodeChange(lower)) {
      if (caps != null && caps.localTools()) {
        return confirm(
            "LOCAL_CODE_TASK",
            0.84,
            "local_code_task",
            Map.of("spec", message),
            "Handle this as a local working-tree task? The client will still enforce tool"
                + " approvals.");
      }
      Map<String, Object> args =
          request != null && request.projectId() != null && !request.projectId().isBlank()
              ? Map.of("spec", message, "projectId", request.projectId())
              : Map.of("spec", message);
      return confirm(
          "CREATE_RUN",
          0.8,
          "create_run",
          args,
          "Create a cloud run? No run credits are charged until the generated plan is approved.");
    }

    if (containsAny(lower, "project", "link repo", "this repo")) {
      String gitRemote =
          request == null
                  || request.repoContext() == null
                  || request.repoContext().gitRemote() == null
              ? ""
              : request.repoContext().gitRemote();
      return action(
          "RESOLVE_PROJECT",
          0.76,
          new TypedAction("resolve_project", Map.of("gitRemote", gitRemote), false, "none", null),
          "I will resolve the current repository against linked ExecuteSpec projects.");
    }

    return chat(
        "I can answer this conversationally, or turn it into a typed action if you ask to run,"
            + " approve, inspect, or change something.",
        0.62);
  }

  private static IntentDecision chat(String response, double confidence) {
    return new IntentDecision("CHAT", confidence, false, null, null, response);
  }

  private static IntentDecision action(
      String intent, double confidence, TypedAction action, String response) {
    return new IntentDecision(intent, confidence, false, null, action, response);
  }

  private static IntentDecision confirm(
      String intent,
      double confidence,
      String actionType,
      Map<String, Object> args,
      String confirmationPrompt) {
    return new IntentDecision(
        intent,
        confidence,
        true,
        confirmationPrompt,
        new TypedAction(actionType, args, true, billingImpact(actionType), confirmationPrompt),
        null);
  }

  private static String billingImpact(String actionType) {
    return switch (actionType) {
      case "approve_run" -> "run_credit";
      case "create_run" -> "run_credit_on_plan_approval";
      case "local_code_task" -> "conversation_usage";
      default -> "none";
    };
  }

  private static boolean looksLikeCodeChange(String lower) {
    return containsAny(
        lower,
        "fix ",
        "implement ",
        "add ",
        "update ",
        "change ",
        "refactor ",
        "debug ",
        "failing test",
        "failing tests",
        "make ");
  }

  private static boolean containsAny(String haystack, String... needles) {
    for (String n : needles) {
      if (haystack.contains(n)) return true;
    }
    return false;
  }

  private static String extractRunId(String message) {
    Matcher m = UUIDISH.matcher(message);
    return m.find() ? m.group() : null;
  }
}
