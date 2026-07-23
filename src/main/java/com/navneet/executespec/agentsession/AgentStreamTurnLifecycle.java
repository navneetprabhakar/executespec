package com.navneet.executespec.agentsession;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.entity.AgentUsageLedger;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.agentsession.repository.AgentUsageLedgerRepository;
import com.navneet.executespec.cost.RunCostTracker;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class AgentStreamTurnLifecycle {

  private final AgentTurnRepository turnRepo;
  private final AgentEventRecorder eventRecorder;
  private final AgentUsageLedgerRepository usageRepo;

  void complete(
      AgentSessionService.StreamTurnContext ctx,
      String assistantSummary,
      RunCostTracker.Snapshot cost,
      String status) {
    AgentTurn turn =
        turnRepo
            .findById(ctx.turnId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "turn not found"));
    // RR-489 — settle a stream turn at most once. A callback, client retry or provider/emitter
    // race can re-enter complete(); without this compare-and-set from STREAMING the same provider
    // spend was appended to the usage ledger and completion event stream on every call. This is an
    // application-level CAS; a hard cross-JVM guarantee still requires a unique (source_type,
    // source_id) ledger constraint (migration, deferred).
    if (!"STREAMING".equals(turn.getStatus())) {
      return;
    }
    turn.setAssistantSummary(AgentText.firstNonBlank(assistantSummary, turn.getAssistantSummary()));
    turn.setInputTokens(cost == null ? 0 : cost.inputTokens());
    turn.setOutputTokens(cost == null ? 0 : cost.outputTokens());
    turn.setReasoningTokens(cost == null ? 0 : cost.reasoningTokens());
    turn.setCostUsd(cost == null ? 0.0 : cost.costUsd());
    turn.setStatus(AgentText.firstNonBlank(status, "OK"));
    turnRepo.save(turn);

    recordUsage(ctx, cost);

    Map<String, Object> completionEvidence = new LinkedHashMap<>();
    completionEvidence.put("provider", ctx.provider());
    completionEvidence.put("model", ctx.model());
    completionEvidence.put("inputTokens", cost == null ? 0 : cost.inputTokens());
    completionEvidence.put("outputTokens", cost == null ? 0 : cost.outputTokens());
    completionEvidence.put("reasoningTokens", cost == null ? 0 : cost.reasoningTokens());
    completionEvidence.put("cacheReadTokens", cost == null ? 0 : cost.cacheReadTokens());
    completionEvidence.put("costUsd", cost == null ? 0.0 : cost.costUsd());
    eventRecorder.append(ctx.sessionId(), ctx.turnId(), "STREAM_COMPLETED", completionEvidence);
  }

  void fail(AgentSessionService.StreamTurnContext ctx, String message) {
    fail(ctx, message, null);
  }

  void fail(
      AgentSessionService.StreamTurnContext ctx, String message, RunCostTracker.Snapshot cost) {
    turnRepo
        .findById(ctx.turnId())
        .ifPresent(
            turn -> {
              // RR-489/RR-1453 — only settle a STREAMING turn: the terminal transition and its
              // usage-ledger row must fire once even if fail() is retried, and a paid failure must
              // still record its spend rather than persist zero.
              if (!"STREAMING".equals(turn.getStatus())) {
                return;
              }
              turn.setStatus("ERROR");
              turn.setAssistantSummary(
                  AgentText.firstNonBlank(message, turn.getAssistantSummary()));
              if (cost != null) {
                turn.setInputTokens(cost.inputTokens());
                turn.setOutputTokens(cost.outputTokens());
                turn.setReasoningTokens(cost.reasoningTokens());
                turn.setCostUsd(cost.costUsd());
              }
              turnRepo.save(turn);
              if (hasSpend(cost)) {
                recordUsage(ctx, cost);
              }
            });
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("message", message == null ? "" : message);
    if (cost != null) {
      payload.put("inputTokens", cost.inputTokens());
      payload.put("outputTokens", cost.outputTokens());
      payload.put("reasoningTokens", cost.reasoningTokens());
      payload.put("cacheReadTokens", cost.cacheReadTokens());
      payload.put("costUsd", cost.costUsd());
    }
    eventRecorder.append(ctx.sessionId(), ctx.turnId(), "STREAM_FAILED", payload);
  }

  private static boolean hasSpend(RunCostTracker.Snapshot cost) {
    return cost != null
        && (cost.inputTokens() > 0 || cost.outputTokens() > 0 || cost.costUsd() > 0);
  }

  private void recordUsage(
      AgentSessionService.StreamTurnContext ctx, RunCostTracker.Snapshot cost) {
    usageRepo.save(
        AgentUsageLedger.builder()
            .id(UUID.randomUUID().toString())
            .orgId(ctx.orgId())
            .userId(ctx.userId())
            .sourceType("AGENT_TURN")
            .sourceId(ctx.turnId())
            .provider(ctx.provider())
            .model(ctx.model())
            .inputTokens(cost == null ? 0 : cost.inputTokens())
            .outputTokens(cost == null ? 0 : cost.outputTokens())
            .reasoningTokens(cost == null ? 0 : cost.reasoningTokens())
            .cacheReadTokens(cost == null ? 0 : cost.cacheReadTokens())
            .costUsd(cost == null ? 0.0 : cost.costUsd())
            .billableUnits(0)
            .billingPolicy("INCLUDED")
            .createdAt(Instant.now())
            .build());
  }

  void disconnect(AgentSessionService.StreamTurnContext ctx) {
    disconnect(ctx, null);
  }

  void disconnect(AgentSessionService.StreamTurnContext ctx, String partialSummary) {
    disconnect(ctx, partialSummary, null);
  }

  void disconnect(
      AgentSessionService.StreamTurnContext ctx,
      String partialSummary,
      RunCostTracker.Snapshot cost) {
    String boundedPartial = boundPartialSummary(partialSummary);
    turnRepo
        .findById(ctx.turnId())
        .ifPresent(
            turn -> {
              if ("STREAMING".equals(turn.getStatus())) {
                turn.setStatus("CANCELLED");
                turn.setAssistantSummary(
                    AgentText.firstNonBlank(
                        boundedPartial == null ? "Stream disconnected." : boundedPartial,
                        turn.getAssistantSummary()));
                if (cost != null) {
                  turn.setInputTokens(cost.inputTokens());
                  turn.setOutputTokens(cost.outputTokens());
                  turn.setReasoningTokens(cost.reasoningTokens());
                  turn.setCostUsd(cost.costUsd());
                }
                turnRepo.save(turn);
                if (hasSpend(cost)) {
                  recordUsage(ctx, cost);
                }
              }
            });
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("message", "client disconnected");
    payload.put("partial", boundedPartial != null);
    if (boundedPartial != null) payload.put("partialSummary", boundedPartial);
    if (cost != null) {
      payload.put("inputTokens", cost.inputTokens());
      payload.put("outputTokens", cost.outputTokens());
      payload.put("reasoningTokens", cost.reasoningTokens());
      payload.put("cacheReadTokens", cost.cacheReadTokens());
      payload.put("costUsd", cost.costUsd());
    }
    eventRecorder.append(ctx.sessionId(), ctx.turnId(), "STREAM_DISCONNECTED", payload);
  }

  private static String boundPartialSummary(String value) {
    if (value == null || value.isBlank()) return null;
    int maxChars = 16_000;
    return value.length() <= maxChars ? value : value.substring(0, maxChars);
  }
}
