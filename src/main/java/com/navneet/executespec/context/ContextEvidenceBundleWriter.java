package com.navneet.executespec.context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Component;

/**
 * Writes the bounded, machine-readable handoff produced by {@link ContextAgent}.
 *
 * <p>The markdown context and decisions journal are useful to a model, but are awkward for a
 * reconnecting client or a later run to validate. This sidecar records the exact evidence inputs
 * and SHA-256 fingerprints of the two durable markdown sources. Consumers can therefore reject a
 * stale handoff instead of silently replaying evidence from an earlier workspace state.
 *
 * <p>The file is written with a temp-and-rename sequence so a crash cannot leave a partially
 * written dossier. Lists and free-form text are bounded here because this artifact is intended for
 * handoff/recovery, not as an unbounded run log.
 */
@Component
public class ContextEvidenceBundleWriter {

  public static final int SCHEMA_VERSION = 1;
  public static final String FILE_NAME = "evidence-bundle.json";
  static final int MAX_TEXT_CHARS = 12_000;
  static final int MAX_LIST_ITEMS = 200;
  static final int MAX_ITEM_CHARS = 1_000;

  private final ObjectMapper objectMapper;

  public ContextEvidenceBundleWriter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** Writes a new bundle and returns its target path. */
  public Path write(Path projectPath, ContextAgentInput input, String runId) throws IOException {
    if (projectPath == null) throw new IllegalArgumentException("projectPath is required");
    if (input == null) throw new IllegalArgumentException("input is required");
    if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId is required");

    Path contextDir = projectPath.resolve(".agent-context");
    Files.createDirectories(contextDir);
    Path target = contextDir.resolve(FILE_NAME);
    ObjectNode root = objectMapper.createObjectNode();
    root.put("schemaVersion", SCHEMA_VERSION);
    root.put("runId", runId);
    root.put("capturedAt", Instant.now().toString());
    root.put("goal", cap(input.runGoal(), MAX_TEXT_CHARS));

    ObjectNode sources = root.putObject("sources");
    sources.put(
        "contextDocumentSha256",
        sha256(readSource(contextDir.resolve("CODEBASE_CONTEXT.md"))));
    sources.put("decisionsSha256", sha256(readSource(contextDir.resolve("decisions.md"))));

    ObjectNode evidence = root.putObject("evidence");
    evidence.put("gitDiffSummary", cap(input.gitDiffSummary(), MAX_TEXT_CHARS));
    putList(evidence, "newPackages", input.newPackagesDetected());
    putList(evidence, "newFiles", input.newFilesDetected());
    putList(evidence, "workerReports", input.workerReportSummaries());
    evidence.put("reviewerSummary", cap(input.reviewerSummary(), MAX_TEXT_CHARS));
    evidence.put("recentDecisions", cap(input.recentDecisions(), MAX_TEXT_CHARS));

    Path temp = contextDir.resolve(FILE_NAME + ".tmp-" + safeRunId(runId));
    Files.writeString(
        temp,
        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
    try {
      Files.move(
          temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }
    return target;
  }

  /**
   * Loads a bundle only when it belongs to {@code runId} and both source fingerprints still match.
   * An empty result means missing, malformed, or stale evidence.
   */
  public Optional<JsonNode> readCurrent(Path projectPath, String runId) {
    if (projectPath == null || runId == null || runId.isBlank()) return Optional.empty();
    Path contextDir = projectPath.resolve(".agent-context");
    Path target = contextDir.resolve(FILE_NAME);
    try {
      JsonNode root = objectMapper.readTree(Files.readString(target));
      if (root == null || root.path("schemaVersion").asInt(-1) != SCHEMA_VERSION) {
        return Optional.empty();
      }
      if (!runId.equals(root.path("runId").asText())) return Optional.empty();
      JsonNode sources = root.path("sources");
      String contextHash = sha256(readSource(contextDir.resolve("CODEBASE_CONTEXT.md")));
      String decisionsHash = sha256(readSource(contextDir.resolve("decisions.md")));
      if (!contextHash.equals(sources.path("contextDocumentSha256").asText())
          || !decisionsHash.equals(sources.path("decisionsSha256").asText())) {
        return Optional.empty();
      }
      return Optional.of(root);
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  private void putList(ObjectNode parent, String name, List<String> values) {
    ArrayNode array = parent.putArray(name);
    if (values == null) return;
    LinkedHashSet<String> deduped = new LinkedHashSet<>();
    for (String value : values) {
      if (value == null || value.isBlank()) continue;
      deduped.add(cap(value.strip(), MAX_ITEM_CHARS));
      if (deduped.size() == MAX_LIST_ITEMS) break;
    }
    deduped.forEach(array::add);
  }

  private static String readSource(Path path) throws IOException {
    return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
  }

  private static String cap(String value, int max) {
    if (value == null) return "";
    return value.length() <= max ? value : value.substring(0, max) + "\n[truncated]";
  }

  private static String safeRunId(String runId) {
    return runId.replaceAll("[^A-Za-z0-9_.-]", "_");
  }

  static String sha256(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(64);
      for (byte b : digest) hex.append(String.format("%02x", b));
      return hex.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
