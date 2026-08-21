package com.navneet.executespec.harness;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationDispatchAuthority;

/** Bounded, workspace-local metadata for an actual provider/model continuation handoff. */
final class ProviderHandoffDossier {

  static final int SCHEMA_VERSION = 1;
  private static final String FILE_NAME = "provider-handoff.json";
  private static final int MAX_TEXT = 800;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ProviderHandoffDossier() {}

  static void write(
      ExecutionContext context,
      Path workspace,
      String runId,
      String taskId,
      String fromProvider,
      String fromModel,
      String toProvider,
      String toModel,
      String decision,
      String reason,
      String failure) {
    if (workspace == null || runId == null || taskId == null) return;
    context.assertWorkspaceGenerationDispatch(WorkspaceGenerationDispatchAuthority.Phase.MUTATION);
    Path target = workspace.resolve(".agent-context").resolve(FILE_NAME);
    Path temp = target.resolveSibling(FILE_NAME + ".tmp");
    try {
      Files.createDirectories(target.getParent());
      ObjectNode node = MAPPER.createObjectNode();
      node.put("schemaVersion", SCHEMA_VERSION);
      node.put("runId", cap(runId));
      node.put("taskId", cap(taskId));
      node.put("fromProvider", cap(fromProvider));
      node.put("fromModel", cap(fromModel));
      node.put("toProvider", cap(toProvider));
      node.put("toModel", cap(toModel));
      node.put("decision", cap(decision));
      node.put("reason", cap(reason));
      node.put("failure", cap(failure));
      node.put("writtenAt", java.time.Instant.now().toString());
      Files.writeString(
          temp,
          MAPPER.writeValueAsString(node) + "\n",
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
        // Best effort; continuation remains valid without the dossier.
      }
    }
  }

  static Optional<JsonNode> read(Path workspace, String runId, String taskId) {
    if (workspace == null || runId == null || taskId == null) return Optional.empty();
    try {
      Path path = workspace.resolve(".agent-context").resolve(FILE_NAME);
      JsonNode node = MAPPER.readTree(Files.readString(path));
      if (node == null
          || node.path("schemaVersion").asInt(-1) != SCHEMA_VERSION
          || !runId.equals(node.path("runId").asText())
          || !taskId.equals(node.path("taskId").asText())) {
        return Optional.empty();
      }
      return Optional.of(node);
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }

  static String recoveryNote(JsonNode node) {
    if (node == null) return "";
    return "PROVIDER HANDOFF CONTEXT (durable dossier): previous route "
        + node.path("fromProvider").asText("unknown")
        + "/"
        + node.path("fromModel").asText("unknown")
        + " handed this task to "
        + node.path("toProvider").asText("unknown")
        + "/"
        + node.path("toModel").asText("unknown")
        + ". Decision: "
        + node.path("decision").asText("unknown")
        + ". Reason: "
        + node.path("reason").asText("not recorded")
        + ". Do not repeat the failed approach blindly; inspect the existing workspace and verify"
        + " the stated failure before editing.";
  }

  static String writeAndReadRecoveryNote(
      ExecutionContext context,
      Path workspace,
      String runId,
      String taskId,
      String fromProvider,
      String fromModel,
      String toProvider,
      String toModel,
      String decision,
      String reason,
      String failure) {
    write(
        context,
        workspace,
        runId,
        taskId,
        fromProvider,
        fromModel,
        toProvider,
        toModel,
        decision,
        reason,
        failure);
    return read(workspace, runId, taskId).map(ProviderHandoffDossier::recoveryNote).orElse(null);
  }

  private static String cap(String value) {
    if (value == null) return "";
    return value.length() <= MAX_TEXT ? value : value.substring(0, MAX_TEXT) + "…";
  }
}
