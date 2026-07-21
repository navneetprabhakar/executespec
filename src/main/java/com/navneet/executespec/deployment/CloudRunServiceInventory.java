package com.navneet.executespec.deployment;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Enumerates the Cloud Run services ExecuteSpec created for previews.
 *
 * <p>Split out of {@link CloudRunClient}, which owns deploy / rollback / delete — the mutating
 * lifecycle. This class owns the read-only inventory that orphan cleanup runs against: which
 * preview services exist, and what labels they carry (the TTL cleanup reads the expiry label from
 * here).
 *
 * <p>Every name that leaves this class is validated against the Cloud Run service-name grammar
 * before it is returned, so a malformed or injected line in gcloud output cannot become an argument
 * to a later delete.
 */
@Slf4j
class CloudRunServiceInventory {

  /** gcloud filter that selects only services this product deployed. */
  private static final String PREVIEW_FILTER = "metadata.labels.managed-by=executespec-preview";

  private final DeploymentProperties props;
  private final ObjectMapper objectMapper;

  CloudRunServiceInventory(DeploymentProperties props, ObjectMapper objectMapper) {
    this.props = props;
    this.objectMapper = objectMapper;
  }

  /** Names of every preview-labelled Cloud Run service in the configured project/region. */
  List<String> listPreviewServices() throws IOException, InterruptedException {
    ProcessBuilder pb = listCommand("value(metadata.name)");
    Process process = pb.start();
    String output = new String(process.getInputStream().readAllBytes()).trim();
    boolean finished = process.waitFor(2, TimeUnit.MINUTES);
    if (!finished) {
      process.destroyForcibly();
      log.warn("Listing preview services timed out");
      return List.of();
    }

    if (process.exitValue() != 0) {
      log.warn("Failed to list preview services: {}", output);
      return List.of();
    }

    if (output.isBlank()) return List.of();
    return Arrays.stream(output.split("\n"))
        .map(String::trim)
        .filter(CloudRunServiceInventory::isValidCloudRunServiceName)
        .toList();
  }

  /** Service name → labels, for TTL-based orphan cleanup. */
  Map<String, Map<String, String>> listPreviewServicesWithLabels()
      throws IOException, InterruptedException {
    ProcessBuilder pb = listCommand("json(metadata.name,metadata.labels)");
    Process process = pb.start();
    String output = new String(process.getInputStream().readAllBytes()).trim();
    boolean finished = process.waitFor(2, TimeUnit.MINUTES);
    if (!finished) {
      process.destroyForcibly();
      log.warn("Listing preview services with labels timed out");
      return Map.of();
    }
    String json = extractJsonArray(output);
    if (process.exitValue() != 0 || json.isBlank()) {
      return Map.of();
    }

    Map<String, Map<String, String>> result = new LinkedHashMap<>();
    try {
      JsonNode arr = objectMapper.readTree(json);
      for (JsonNode svc : arr) {
        String name = svc.path("metadata").path("name").asText(null);
        if (name == null || !isValidCloudRunServiceName(name)) continue;
        Map<String, String> labels = new HashMap<>();
        JsonNode labelsNode = svc.path("metadata").path("labels");
        labelsNode.fields().forEachRemaining(e -> labels.put(e.getKey(), e.getValue().asText()));
        result.put(name, labels);
      }
    } catch (Exception e) {
      log.warn("Failed to parse preview services labels: {}", e.getMessage());
    }
    return result;
  }

  private ProcessBuilder listCommand(String format) {
    return new ProcessBuilder(
            "gcloud",
            "run",
            "services",
            "list",
            "--project",
            props.gcpProject(),
            "--region",
            props.gcpRegion(),
            "--filter",
            PREVIEW_FILTER,
            "--format",
            format,
            "--quiet")
        .redirectErrorStream(true);
  }

  private static boolean isValidCloudRunServiceName(String value) {
    return value != null && value.matches("[a-z]([-a-z0-9]{0,61}[a-z0-9])?");
  }

  private static String extractJsonArray(String output) {
    if (output == null || output.isBlank()) {
      return "";
    }
    int start = output.indexOf('[');
    int end = output.lastIndexOf(']');
    if (start < 0 || end < start) {
      return "";
    }
    return output.substring(start, end + 1);
  }
}
