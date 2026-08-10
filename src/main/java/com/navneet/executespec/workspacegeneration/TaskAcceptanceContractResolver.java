package com.navneet.executespec.workspacegeneration;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Produces canonical, model-independent authority and per-task acceptance policy digests. */
@Component
@RequiredArgsConstructor
public class TaskAcceptanceContractResolver {

  private final ObjectMapper objectMapper;

  public TaskAcceptanceContract resolve(PlannedTask task, int taskIndex) {
    String testRequirement = testsRequired(task) ? "REQUIRED" : "OPTIONAL";
    List<String> touchPaths = task.touchesFiles() == null ? List.of() : task.touchesFiles();
    Map<String, Object> unsigned =
        Map.of(
            "schemaVersion",
            1,
            "taskId",
            task.id(),
            "taskIndex",
            taskIndex,
            "verificationPolicy",
            "SERVER_RESOLVED_V1",
            "buildRequirement",
            "REQUIRED_NON_NOOP",
            "testRequirement",
            testRequirement,
            "declaredTouchPaths",
            touchPaths.stream().sorted().toList());
    String digest = sha256(canonical(unsigned));
    return new TaskAcceptanceContract(
        1,
        task.id(),
        taskIndex,
        "SERVER_RESOLVED_V1",
        "REQUIRED_NON_NOOP",
        testRequirement,
        touchPaths.stream().sorted().toList(),
        digest);
  }

  /** Binds attempts to the exact plan, original goal, and workspace selection admitted to BUILD. */
  public String authorityDigest(Plan plan, String originalGoal, String workspaceSummary) {
    return sha256(
        canonical(
            Map.of(
                "schemaVersion",
                1,
                "plan",
                plan,
                "originalGoal",
                originalGoal == null ? "" : originalGoal,
                "workspaceSummary",
                workspaceSummary == null ? "" : workspaceSummary)));
  }

  private boolean testsRequired(PlannedTask task) {
    List<String> paths = task.touchesFiles() == null ? List.of() : task.touchesFiles();
    if (paths.stream().anyMatch(TaskAcceptanceContractResolver::isCodePath)) return true;
    String text =
        ((task.title() == null ? "" : task.title())
                + " "
                + (task.description() == null ? "" : task.description()))
            .toLowerCase(Locale.ROOT);
    boolean staticOnly =
        text.contains("documentation")
            || text.contains("readme")
            || text.contains("static asset")
            || text.contains("copy change")
            || text.contains("configuration only")
            || text.contains("config-only");
    return !staticOnly;
  }

  static boolean isCodePath(String path) {
    if (path == null) return false;
    String normalized = path.toLowerCase(Locale.ROOT);
    return normalized.matches(
        ".*\\.(java|kt|kts|scala|groovy|js|jsx|ts|tsx|py|go|rs|rb|php|cs|cpp|cc|c|h|hpp|swift|ex|exs)$");
  }

  private String canonical(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException impossible) {
      throw new IllegalStateException("TASK_ACCEPTANCE_CONTRACT_SERIALIZATION_FAILED", impossible);
    }
  }

  private static String sha256(String value) {
    return DigestUtils.sha256Hex(value.getBytes(StandardCharsets.UTF_8));
  }
}
