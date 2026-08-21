package com.navneet.executespec.workspacegeneration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;

import org.apache.commons.codec.digest.DigestUtils;

/** Canonical serialization and durable-archive identity rules for generation evidence. */
final class WorkspaceGenerationEvidenceCodec {

  private WorkspaceGenerationEvidenceCodec() {}

  static String repositories(
      ObjectMapper objectMapper,
      Path workspaceRoot,
      CorrectionWorkspaceTransaction.Checkpoint checkpoint) {
    return write(
        objectMapper,
        checkpoint.repositories().stream()
            .map(
                repository ->
                    Map.of(
                        "repository", workspaceRoot.relativize(repository.repository()).toString(),
                        "head", repository.head(),
                        "tree", repository.tree()))
            .toList());
  }

  static CorrectionWorkspaceTransaction.Checkpoint canonicalCheckpoint(
      Path canonicalRoot, CorrectionWorkspaceTransaction.Checkpoint checkpoint) {
    return new CorrectionWorkspaceTransaction.Checkpoint(
        canonicalRoot,
        checkpoint.repositories().stream()
            .map(
                repository -> {
                  Path canonicalRepository = realPath(repository.repository());
                  if (!canonicalRepository.startsWith(canonicalRoot)) {
                    throw new IllegalStateException("WORKSPACE_GENERATION_REPOSITORY_ESCAPES_ROOT");
                  }
                  return new CorrectionWorkspaceTransaction.RepositoryCheckpoint(
                      canonicalRepository, repository.head(), repository.tree());
                })
            .toList());
  }

  static Path realPath(Path path) {
    try {
      return path.toRealPath();
    } catch (java.io.IOException unavailable) {
      throw new IllegalStateException("WORKSPACE_GENERATION_PATH_UNAVAILABLE", unavailable);
    }
  }

  static String canonicalJson(ObjectMapper objectMapper, String value) {
    try {
      return objectMapper.writeValueAsString(
          objectMapper.readTree(requireText(value, "JSON_EMPTY")));
    } catch (JsonProcessingException malformed) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_JSON_INVALID", malformed);
    }
  }

  static List<UUID> commandReceiptIds(ObjectMapper objectMapper, String evidenceJson) {
    try {
      var node = objectMapper.readTree(evidenceJson).path("commandReceiptIds");
      if (!node.isArray()) return List.of();
      List<UUID> ids = new ArrayList<>();
      for (var value : node) ids.add(UUID.fromString(value.asText()));
      return List.copyOf(ids);
    } catch (JsonProcessingException | IllegalArgumentException malformed) {
      throw new IllegalArgumentException(
          "WORKSPACE_VERIFICATION_COMMAND_RECEIPTS_INVALID", malformed);
    }
  }

  static String write(ObjectMapper objectMapper, Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException impossible) {
      throw new IllegalStateException("WORKSPACE_GENERATION_JSON_WRITE_FAILED", impossible);
    }
  }

  static String sha256(String value) {
    return DigestUtils.sha256Hex(value.getBytes(StandardCharsets.UTF_8));
  }

  static String archiveObjectKey(String runId, UUID attemptId, String phase, String generation) {
    return "workspace-generations/"
        + DigestUtils.sha256Hex(runId)
        + "/"
        + attemptId
        + "/"
        + phase
        + "-"
        + generation
        + ".tar.gz";
  }

  static WorkspaceGenerationStepStore.ArchiveReceipt archiveReceipt(
      com.navneet.executespec.deployment.GcsTaskCheckpointService.CheckpointReceipt receipt) {
    return new WorkspaceGenerationStepStore.ArchiveReceipt(
        receipt.key(),
        receipt.artifactDigest(),
        receipt.storageGeneration(),
        receipt.sizeBytes(),
        receipt.crc32c(),
        receipt.etag());
  }

  private static String requireText(String value, String code) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(code);
    return value;
  }
}
