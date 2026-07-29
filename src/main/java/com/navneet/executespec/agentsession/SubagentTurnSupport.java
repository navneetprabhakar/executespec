package com.navneet.executespec.agentsession;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.agentsession.subagent.SubagentProtocol.Evidence;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol.Role;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.specchat.SpecChatService;

/** Prompt, evidence, and result-capture mechanics shared by bounded read-only subagent turns. */
final class SubagentTurnSupport {

  private static final Pattern CITATION =
      Pattern.compile("(?m)([A-Za-z0-9_./-]+\\.[A-Za-z0-9_-]+):(\\d+)");

  private SubagentTurnSupport() {}

  static String rolePrompt(Role role, String objective) {
    String contract =
        switch (role) {
          case SCOUT ->
              "Locate the smallest relevant code surface. Report findings only; do not propose or"
                  + " perform edits. Cite every concrete finding as path:line.";
          case VERIFIER ->
              "Independently verify the stated objective against repository evidence. Do not edit,"
                  + " run lifecycle actions, or claim success without path:line citations.";
          case REPRODUCER ->
              "Trace the smallest deterministic reproduction path using repository evidence only."
                  + " Do not execute commands or edit files; report prerequisites, expected failure"
                  + " signatures, and path:line evidence.";
          case REVIEWER ->
              "Review the relevant implementation for correctness, regressions, and missing tests."
                  + " Do not edit or run lifecycle actions; report findings with path:line"
                  + " evidence.";
          case DOCS_SCOUT ->
              "Find the authoritative documentation and contract surfaces relevant to the"
                  + " objective. Do not edit; report stale or missing documentation with path:line"
                  + " evidence.";
          case IMPLEMENTER ->
              "Implementer execution is only available through the explicit single-writer"
                  + " mutation seam; do not use this read-only child executor.";
        };
    return "ROLE: " + role + " (strictly read-only)\n" + contract + "\n\nOBJECTIVE:\n" + objective;
  }

  static List<Evidence> extractEvidence(String summary, List<String> toolEvidence) {
    Set<String> citations = new LinkedHashSet<>();
    collectCitations(summary, citations);
    for (String value : toolEvidence) collectCitations(value, citations);
    return citations.stream()
        .limit(20)
        .map(citation -> new Evidence(citation, "read-only subagent evidence"))
        .toList();
  }

  private static void collectCitations(String value, Set<String> citations) {
    if (value == null) return;
    Matcher matcher = CITATION.matcher(value);
    while (matcher.find()) citations.add(matcher.group(1) + ":" + matcher.group(2));
  }

  static String safeMessage(RuntimeException failure) {
    String message = failure.getMessage();
    return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
  }

  static final class CapturingSink implements SpecChatService.Sink {
    private final StringBuilder text = new StringBuilder();
    private final List<String> toolEvidence = new ArrayList<>();
    private final Map<String, String> toolNames = new LinkedHashMap<>();
    private RunCostTracker.Snapshot usage = new RunCostTracker.Snapshot(0, 0, 0, 0, 0, 0);
    private int turnCount;

    @Override
    public void token(String value) {
      if (value == null || value.isBlank()) return;
      if (!text.isEmpty()) text.append('\n');
      text.append(value);
    }

    @Override
    public void toolCall(String tool, String argsJson, String id) {
      toolNames.put(id, tool);
    }

    @Override
    public void toolResult(String id, String resultJson) {
      toolEvidence.add(toolNames.getOrDefault(id, "tool") + ": " + resultJson);
    }

    @Override
    public void done(int completedTurns, RunCostTracker.Snapshot cost) {
      turnCount = completedTurns;
      usage = cost == null ? usage : cost;
    }

    String summary() {
      return text.isEmpty()
          ? "Read-only subagent completed without a textual summary."
          : text.toString();
    }

    List<String> toolEvidence() {
      return List.copyOf(toolEvidence);
    }

    RunCostTracker.Snapshot usage() {
      return usage;
    }

    int turnCount() {
      return turnCount;
    }
  }
}
