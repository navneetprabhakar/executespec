package com.navneet.executespec.integration;

import static com.navneet.executespec.integration.RunPipelineSupport.truncate;

import java.nio.file.Path;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.lint.LintPass;
import com.navneet.executespec.router.ModelProviderScore;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.repository.AgentTaskRepository;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns the durable state transitions around an automatic correction attempt.
 *
 * <p>The orchestrator decides whether and how to execute a correction; this collaborator owns its
 * reversible workspace checkpoint and the task action ledger. Keeping those mechanics together
 * prevents execution flow from accidentally accepting a workspace without recording its
 * content-addressed before/after generations.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutomaticCorrectionLifecycle {

  private final CorrectionWorkspaceTransaction workspaceTransaction;
  private final AgentTaskRepository taskRepository;
  private final ObjectMapper objectMapper;
  private final FeatureFlagService featureFlagService;
  private final LintPass lintPass;
  private final SseBroadcaster broadcaster;

  public CorrectionWorkspaceTransaction.Checkpoint begin(Path workspaceRoot) {
    return workspaceTransaction.begin(workspaceRoot);
  }

  public CorrectionWorkspaceTransaction.Accepted accept(
      CorrectionWorkspaceTransaction.Checkpoint checkpoint) {
    return workspaceTransaction.accept(checkpoint);
  }

  public void rollback(CorrectionWorkspaceTransaction.Checkpoint checkpoint) {
    workspaceTransaction.rollback(checkpoint);
  }

  static boolean sameRoute(
      ModelProviderScore candidate, String dispatchedProvider, String dispatchedModel) {
    return candidate != null
        && candidate
            .provider()
            .equalsIgnoreCase(dispatchedProvider == null ? "" : dispatchedProvider)
        && candidate.model().equalsIgnoreCase(dispatchedModel == null ? "" : dispatchedModel);
  }

  /** Runs the optional formatting pass inside the still-reversible correction transaction. */
  public void runLintAutofix(String runId, Path workspaceRoot) {
    if (!featureFlagService.isEnabled("lint.autofix.enabled")) {
      return;
    }
    try {
      LintPass.LintReport lintReport = lintPass.run(runId, workspaceRoot);
      if (lintReport.changedAnything()) {
        log.info("Automatic correction run={} lint auto-fix: {}", runId, lintReport.summary());
        broadcaster.emit(runId, RunEvent.lintAutofixApplied(runId, lintReport.summary()));
      } else if (!lintReport.skipped()) {
        log.debug("Automatic correction run={} lint auto-fix: no changes", runId);
      }
    } catch (Exception e) {
      log.warn(
          "Automatic correction run={} lint auto-fix failed (continuing): {}",
          runId,
          e.getMessage());
    }
  }

  public void recordExecuted(
      AgentTask task,
      String decision,
      String provider,
      String model,
      String reason,
      String limitName,
      int usedLimit,
      int maxLimit,
      String source) {
    if (task == null || decision == null) {
      return;
    }
    try {
      ArrayNode actions = readActions(task.getContinuationActionsJson());
      ObjectNode action = actions.addObject();
      action.put("status", "EXECUTED");
      action.put("decision", decision);
      action.put("provider", provider);
      action.put("model", model);
      action.put("reason", reason == null ? "" : reason);
      action.put("limitName", limitName);
      action.put("usedLimit", usedLimit);
      action.put("maxLimit", maxLimit);
      action.put("source", source);
      action.put("attempt", actions.size());
      task.setContinuationActionsJson(objectMapper.writeValueAsString(actions));
      taskRepository.saveAndFlush(task);
    } catch (Exception ex) {
      log.warn(
          "automatic correction: failed to append action history for task {}: {}",
          task.getTaskId(),
          ex.getMessage(),
          ex);
    }
  }

  public void recordResolution(
      String taskKey,
      String status,
      String code,
      String reason,
      String beforeGeneration,
      String afterGeneration) {
    if (taskKey == null || taskKey.isBlank()) {
      return;
    }
    try {
      AgentTask task = taskRepository.findById(taskKey).orElse(null);
      if (task == null) {
        return;
      }
      ArrayNode actions = readActions(task.getContinuationActionsJson());
      ObjectNode resolution = actions.addObject();
      resolution.put("status", status);
      resolution.put("decision", "CORRECTION_RESOLUTION");
      resolution.put("code", code == null ? "CORRECTION_UNKNOWN" : code);
      resolution.put("reason", reason == null ? "" : truncate(reason, 500));
      resolution.put("beforeWorkspaceGeneration", beforeGeneration);
      resolution.put("afterWorkspaceGeneration", afterGeneration);
      resolution.put("attempt", actions.size());
      task.setContinuationActionsJson(objectMapper.writeValueAsString(actions));
      taskRepository.saveAndFlush(task);
    } catch (Exception persistenceFailure) {
      throw new IllegalStateException(
          "Could not persist the automatic correction resolution", persistenceFailure);
    }
  }

  public void restoreTask(
      String taskKey, String status, Instant startedAt, Instant completedAt, Long durationMs) {
    if (taskKey == null || taskKey.isBlank()) {
      return;
    }
    try {
      taskRepository
          .findById(taskKey)
          .ifPresent(
              task -> {
                task.setStatus(status);
                task.setStartedAt(startedAt);
                task.setCompletedAt(completedAt);
                task.setDurationMs(durationMs);
                taskRepository.save(task);
              });
    } catch (RuntimeException ex) {
      log.warn(
          "automatic correction: failed to restore task state for {}: {}",
          taskKey,
          ex.getMessage());
    }
  }

  private ArrayNode readActions(String json)
      throws com.fasterxml.jackson.core.JsonProcessingException {
    if (json == null || json.isBlank()) {
      return objectMapper.createArrayNode();
    }
    JsonNode parsed = objectMapper.readTree(json);
    if (parsed != null && parsed.isArray()) {
      ArrayNode copy = objectMapper.createArrayNode();
      parsed.forEach(copy::add);
      return copy;
    }
    return objectMapper.createArrayNode();
  }
}
