package com.navneet.executespec.cost;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Builds the auditable, per-call cost ledger returned by the run cost endpoint. */
@Service
@RequiredArgsConstructor
public class RunCostEvidenceService {

  private final RunStepUsageRepository repository;

  public void enrich(Map<String, Object> body, PipelineRun run) {
    List<RunStepUsage> rows = repository.findByRunIdOrderByCreatedAtAsc(run.getRunId());
    List<Map<String, Object>> stepCalls =
        rows.stream().map(RunCostEvidenceService::toCall).toList();
    double attributed = rows.stream().mapToDouble(RunStepUsage::getCostUsd).sum();
    double total = ((Number) body.getOrDefault("costUsd", 0d)).doubleValue();
    double difference = total - attributed;
    body.put("stepCalls", stepCalls);
    body.put("attributedCostUsd", attributed);
    body.put("unattributedCostUsd", difference);
    body.put("reconciled", Math.abs(difference) < 0.000001d);
    body.put(
        "phases",
        Map.of(
            "classifier", nullToZero(run.getClassifierCostUsd()),
            "outline", nullToZero(run.getOutlineCostUsd()),
            "expansion", nullToZero(run.getExpansionCostUsd()),
            "coherence", nullToZero(run.getCoherenceCostUsd())));
  }

  private static Map<String, Object> toCall(RunStepUsage row) {
    Map<String, Object> call = new LinkedHashMap<>();
    call.put("id", row.getId());
    call.put("taskId", row.getTaskId());
    call.put("agentRole", row.getAgentRole());
    call.put("stepId", row.getStepId());
    call.put("retryOf", row.getRetryOf());
    call.put("llmExecutionContextId", row.getLlmExecutionContextId());
    call.put("orgId", row.getOrgId());
    call.put("userId", row.getUserId());
    call.put("projectId", row.getProjectId());
    call.put("repoId", row.getRepoId());
    call.put("feature", row.getFeature());
    call.put("sourceType", row.getSourceType());
    call.put("sourceId", row.getSourceId());
    call.put("keySource", row.getKeySource());
    call.put("attemptStatus", row.getAttemptStatus());
    call.put("errorCode", row.getErrorCode());
    call.put("error", row.getErrorRedacted());
    call.put("attemptNumber", row.getAttemptNumber());
    call.put("latencyMs", row.getLatencyMs());
    call.put("provider", row.getProvider());
    call.put("model", row.getModel());
    // C1 Step 5 (G5) — causality. Without these a consumer cannot tell a same-provider retry from
    // a cross-provider failover, so a route change looks identical to a repeat of the same route.
    call.put("modelTier", row.getModelTier());
    call.put("attemptKind", row.getAttemptKind());
    call.put("physicalAttemptId", row.getPhysicalAttemptId());
    call.put("parentAttemptId", row.getParentAttemptId());
    call.put("logicalCallId", row.getLogicalCallId());
    call.put("inputTokens", row.getInputTokens());
    call.put("outputTokens", row.getOutputTokens());
    call.put("cacheReadTokens", row.getCacheReadTokens());
    call.put("cacheWriteTokens", row.getCacheWriteTokens());
    call.put("reasoningTokens", row.getReasoningTokens());
    call.put("costUsd", row.getCostUsd());
    call.put("callIndex", row.getCallIndex());
    call.put("createdAt", row.getCreatedAt());
    return call;
  }

  private static double nullToZero(Double value) {
    return value == null ? 0d : value;
  }
}
