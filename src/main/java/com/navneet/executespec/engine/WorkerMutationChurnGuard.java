package com.navneet.executespec.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.tool.ToolCapabilities;
import com.navneet.executespec.tool.ToolCapability;

import lombok.extern.slf4j.Slf4j;

/** Stops repeated same-path mutation from masquerading as useful worker progress. */
@Slf4j
final class WorkerMutationChurnGuard {

  static final int NUDGE_AT = 6;
  static final int FORCE_AT = 10;

  enum Decision {
    NONE,
    NUDGE,
    FORCE
  }

  private final Map<String, Integer> countsSinceVerification = new HashMap<>();
  private final int forceAt;
  private final int nudgeAt;
  private int worst;
  private boolean nudged;

  WorkerMutationChurnGuard() {
    this(FORCE_AT);
  }

  WorkerMutationChurnGuard(int forceAt) {
    this.forceAt = forceAt > 0 ? forceAt : FORCE_AT;
    this.nudgeAt = Math.min(NUDGE_AT, Math.max(1, this.forceAt - 1));
  }

  void recordMutation(String target) {
    if (target == null || target.isBlank()) return;
    worst = Math.max(worst, countsSinceVerification.merge(target, 1, Integer::sum));
  }

  void recordPassingVerification() {
    countsSinceVerification.clear();
    worst = 0;
    nudged = false;
  }

  Decision evaluate(boolean enabled, String runId, String taskId, int iteration) {
    if (!enabled) return Decision.NONE;
    if (worst >= forceAt) {
      log.warn(
          "WorkerAgent MUTATION_CHURN_FORCE run={} task={} iter={} count={} threshold={}",
          runId,
          taskId,
          iteration,
          worst,
          forceAt);
      return Decision.FORCE;
    }
    if (!nudged && worst >= nudgeAt) {
      nudged = true;
      log.warn(
          "WorkerAgent MUTATION_CHURN_NUDGE run={} task={} iter={} count={} threshold={}",
          runId,
          taskId,
          iteration,
          worst,
          nudgeAt);
      return Decision.NUDGE;
    }
    return Decision.NONE;
  }

  static String nudgeMessage() {
    return "MUTATION CHURN CHECK — you have repeatedly rewritten the same path without a passing"
        + " maintained test or build. Stop changing the scratch/check file. Read the latest failure"
        + " once, fix the production cause, put the regression in the conventional test suite, run"
        + " that suite once, then call completeTask. Do not create another ad-hoc smoke script.";
  }

  /** Extract a normalized destination from a file-mutating tool call for durable evidence. */
  static String mutationTarget(ObjectMapper objectMapper, String toolName, String arguments) {
    if (toolName == null || arguments == null) return "";
    if (!ToolCapabilities.of(toolName).map(ToolCapability::mutatesFiles).orElse(false)) return "";
    try {
      JsonNode node = objectMapper.readTree(arguments);
      for (String field : List.of("path", "file", "filePath", "dst", "src")) {
        if (node.hasNonNull(field)) return safeTarget(node.get(field).asText());
      }
      for (String line : node.path("patch").asText("").lines().toList()) {
        String trimmed = line.trim();
        for (String marker : List.of("*** Add File:", "*** Update File:")) {
          if (trimmed.startsWith(marker)) return safeTarget(trimmed.substring(marker.length()));
        }
      }
    } catch (Exception ignored) {
      // Malformed arguments fail closed: no target is attributed to the task.
    }
    return "";
  }

  static String argumentSignature(String args) {
    if (args == null || args.isBlank()) return "none";
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(args.getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest, 0, 8);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static String safeTarget(String path) {
    if (path == null) return "";
    return path.replace('\n', ' ').replace('\r', ' ').replace(']', '_').trim();
  }
}
