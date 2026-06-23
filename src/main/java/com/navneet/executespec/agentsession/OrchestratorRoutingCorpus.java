package com.navneet.executespec.agentsession;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared loader for the orchestrator routing JSONL corpus.
 *
 * <p>The corpus is used by always-on deterministic tests and by optional live model prompt evals.
 */
public final class OrchestratorRoutingCorpus {

  public static final String CLASSPATH_RESOURCE = "orchestrator/routing-corpus.jsonl";

  private OrchestratorRoutingCorpus() {}

  public static List<Row> load(ObjectMapper mapper) {
    var resource =
        OrchestratorRoutingCorpus.class.getClassLoader().getResourceAsStream(CLASSPATH_RESOURCE);
    if (resource == null) {
      throw new IllegalStateException("missing classpath resource " + CLASSPATH_RESOURCE);
    }
    List<Row> rows = new ArrayList<>();
    try (var reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) continue;
        rows.add(mapper.readValue(line, Row.class));
      }
      return rows;
    } catch (IOException e) {
      throw new IllegalStateException("failed to load " + CLASSPATH_RESOURCE, e);
    }
  }

  public record Row(
      String id,
      String message,
      Context context,
      List<String> expectedRoutes,
      boolean forbidRun,
      String severity,
      String notes) {}

  public record Context(
      boolean hasRepo,
      boolean hasProject,
      boolean supportsLocalHands,
      String permissionMode,
      List<String> changedFiles) {

    public Context {
      if (permissionMode == null || permissionMode.isBlank()) {
        permissionMode = "auto";
      }
      if (changedFiles == null) {
        changedFiles = List.of();
      }
    }
  }
}
