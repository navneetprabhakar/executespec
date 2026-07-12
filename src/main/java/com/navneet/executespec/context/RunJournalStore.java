package com.navneet.executespec.context;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Small workspace-local journal for cross-task execution context.
 *
 * <p>The executor keeps the journal in memory for prompt assembly, but a JVM restart otherwise
 * erases the completed-task context. This store is deliberately bounded and atomic: it is a
 * recovery aid, not a second transcript or an authority for task status. Malformed or unavailable
 * files fail closed to an empty journal so they cannot block a run.
 */
public final class RunJournalStore {

  static final int MAX_ENTRIES = 20;
  static final int MAX_ENTRY_CHARS = 512;
  static final int SCHEMA_VERSION = 1;
  static final String JOURNAL_DIRECTORY = ".agent-context";
  static final String JOURNAL_FILE = "run-journal.json";

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

  private RunJournalStore() {}

  /** Load the bounded journal from a run workspace. */
  public static List<String> load(Path workspace) {
    return load(workspace, null);
  }

  /** Load only when the durable envelope belongs to the expected run (fail-closed on mismatch). */
  public static List<String> load(Path workspace, String expectedRunId) {
    if (workspace == null) return List.of();
    try {
      Path path = path(workspace);
      if (!Files.isRegularFile(path)) return List.of();
      JsonNode root = MAPPER.readTree(Files.readString(path));
      if (root == null) return List.of();
      // Read the pre-v2 bare-array representation so upgrading a workspace does not erase its
      // task context. New writes use an envelope, which gives recovery a schema/version boundary
      // instead of treating any JSON array as a trusted journal.
      if (root.isArray()) {
        return sanitize(MAPPER.convertValue(root, STRING_LIST));
      }
      if (!root.isObject() || root.path("schemaVersion").asInt(-1) != SCHEMA_VERSION) {
        return List.of();
      }
      if (expectedRunId != null
          && !expectedRunId.isBlank()
          && !expectedRunId.equals(root.path("runId").asText())) {
        return List.of();
      }
      JsonNode entries = root.path("entries");
      if (!entries.isArray()) return List.of();
      return sanitize(MAPPER.convertValue(entries, STRING_LIST));
    } catch (Exception ignored) {
      return List.of();
    }
  }

  /** Replace the durable journal with the current bounded in-memory state. */
  public static void save(Path workspace, List<String> entries) {
    save(workspace, entries, null);
  }

  /** Persist the bounded journal with the run identity used for restart validation. */
  public static void save(Path workspace, List<String> entries, String runId) {
    if (workspace == null) return;
    Path target = path(workspace);
    Path temp = target.resolveSibling(target.getFileName() + ".tmp");
    try {
      Files.createDirectories(target.getParent());
      List<String> bounded = sanitize(entries);
      ObjectNode envelope = MAPPER.createObjectNode();
      envelope.put("schemaVersion", SCHEMA_VERSION);
      envelope.put("updatedAt", Instant.now().toString());
      if (runId != null && !runId.isBlank()) envelope.put("runId", runId);
      ArrayNode journalEntries = envelope.putArray("entries");
      bounded.forEach(journalEntries::add);
      Files.writeString(
          temp,
          MAPPER.writeValueAsString(envelope) + "\n",
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
    } catch (Exception ignored) {
      try {
        Files.deleteIfExists(temp);
      } catch (Exception ignoredCleanup) {
        // best effort only; the journal never blocks execution
      }
    }
  }

  static Path path(Path workspace) {
    return workspace.resolve(JOURNAL_DIRECTORY).resolve(JOURNAL_FILE);
  }

  private static List<String> sanitize(List<String> entries) {
    if (entries == null || entries.isEmpty()) return List.of();
    List<String> bounded = new ArrayList<>();
    for (String entry : entries) {
      if (entry == null || entry.isBlank()) continue;
      String value = entry.strip();
      if (value.length() > MAX_ENTRY_CHARS) {
        value = value.substring(0, MAX_ENTRY_CHARS) + "…";
      }
      bounded.add(value);
    }
    int from = Math.max(0, bounded.size() - MAX_ENTRIES);
    return List.copyOf(bounded.subList(from, bounded.size()));
  }
}
