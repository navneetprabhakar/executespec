package com.navneet.executespec.engine;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.tool.ToolCapabilities;

/**
 * Small static helpers extracted from {@link WorkerAgent} (a god-class-allowlisted file) so its
 * size stays bounded.
 */
final class WorkerAgentSupport {

  private WorkerAgentSupport() {}

  /** Dispatches a {@code shell} tool call (local or remote) and returns its envelope, or null. */
  @FunctionalInterface
  interface ShellDispatch {
    String dispatch(String shellArgsJson);
  }

  /**
   * RL-2026-06 — reads the candidate deliverable for the critic, workspace-agnostically via ONE
   * {@code shell} dispatch. Remote TB run: an {@code /app}+{@code /tests} listing + required-path
   * contents. Local product run: the worker's {@code git diff}. Fail-soft: returns a placeholder on
   * any error so the critic still runs on the task text alone. Extracted from {@link WorkerAgent}.
   */
  static String readDeliverableContext(
      ShellDispatch dispatch, ObjectMapper objectMapper, boolean remote, List<String> paths) {
    List<String> safe =
        paths.stream()
            .filter(p -> p != null && p.matches("/[A-Za-z0-9._/-]+"))
            .distinct()
            .limit(8)
            .toList();
    StringBuilder cmd = new StringBuilder();
    if (remote) {
      cmd.append(
          "echo '=== /app files ==='; find /app -maxdepth 3 -type f 2>/dev/null | head -40; ");
      cmd.append(
          "echo '=== /tests files ==='; find /tests -maxdepth 3 -type f 2>/dev/null | head -40; ");
      cmd.append(
          "if [ -f /tests/test.sh ]; then echo '=== /tests/test.sh ==='; head -c 4000"
              + " /tests/test.sh; echo; fi; ");
      if (safe.isEmpty()) {
        cmd.append(
            "echo '=== likely /app candidate contents ==='; "
                + "find /app -maxdepth 3 -type f \\( -perm -111 -o -name '*.py' -o -name '*.sh'"
                + " -o -name '*.js' -o -name '*.ts' -o -name '*.json' -o -name '*.txt'"
                + " -o -name '*.csv' -o -name '*.out' -o -name '*.result' \\) 2>/dev/null"
                + " | head -8 | while IFS= read -r f; do echo \"=== $f ===\"; head -c 2000"
                + " \"$f\"; echo; done; ");
      }
    } else {
      cmd.append("echo '=== git diff (stat) ==='; git diff --stat 2>/dev/null | head -40; ");
      cmd.append("echo '=== git diff ==='; git diff 2>/dev/null | head -c 12000; echo; ");
    }
    for (String p : safe) {
      cmd.append("echo '=== ")
          .append(p)
          .append(" ==='; cat '")
          .append(p)
          .append("' 2>/dev/null | head -c 4000; echo; ");
    }
    try {
      ObjectNode args = objectMapper.createObjectNode();
      args.put("command", cmd.toString());
      String envelope = dispatch.dispatch(objectMapper.writeValueAsString(args));
      if (envelope == null) return "(deliverable unavailable)";
      JsonNode env = objectMapper.readTree(envelope);
      String content = env.path("content").asText("");
      if (content.length() > 12000) content = content.substring(0, 12000) + "\n…(truncated)";
      return content.isEmpty() ? "(no deliverable found)" : content;
    } catch (Exception e) {
      return "(deliverable unavailable: " + e.getMessage() + ")";
    }
  }

  /**
   * RR-622 — collapse a model-supplied tool name to a bounded metric tag. Native tool-call names
   * are attacker/provider-controlled strings; recording them raw let a malfunctioning provider emit
   * a unique name per iteration and create unbounded time-series. A name is kept only when it maps
   * to a registered capability alias (a finite set); everything else records {@code "unknown"}.
   */
  static String boundedToolMetricName(String toolName) {
    if (toolName == null || toolName.isBlank()) {
      return "unknown";
    }
    return ToolCapabilities.of(toolName).isPresent() ? toolName : "unknown";
  }

  /**
   * RR-1562 — the tighter of two nullable dollar caps, where null means "unbounded". Non-positive
   * values are treated as unset so a caller cannot accidentally disable enforcement with 0/-1.
   */
  static Double minNullableCap(Double a, Double b) {
    Double left = (a != null && a > 0) ? a : null;
    Double right = (b != null && b > 0) ? b : null;
    if (left == null) return right;
    if (right == null) return left;
    return Math.min(left, right);
  }
}
