package com.navneet.executespec.agentsession;

import java.time.Instant;
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
    turn.setAssistantSummary(AgentText.firstNonBlank(assistantSummary, turn.getAssistantSummary()));
    turn.setInputTokens(cost == null ? 0 : cost.inputTokens());
    turn.setOutputTokens(cost == null ? 0 : cost.outputTokens());
    turn.setReasoningTokens(cost == null ? 0 : cost.reasoningTokens());
    turn.setCostUsd(cost == null ? 0.0 : cost.costUsd());
    turn.setStatus(AgentText.firstNonBlank(status, "OK"));
    turnRepo.save(turn);

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

    eventRecorder.append(ctx.sessionId(), ctx.turnId(), "STREAM_COMPLETED", cost);
  }

  void fail(AgentSessionService.StreamTurnContext ctx, String message) {
    turnRepo
        .findById(ctx.turnId())
        .ifPresent(
            turn -> {
              turn.setStatus("ERROR");
              turn.setAssistantSummary(
                  AgentText.firstNonBlank(message, turn.getAssistantSummary()));
              turnRepo.save(turn);
            });
    eventRecorder.append(
        ctx.sessionId(),
        ctx.turnId(),
        "STREAM_FAILED",
        Map.of("message", message == null ? "" : message));
  }

  void disconnect(AgentSessionService.StreamTurnContext ctx) {
    turnRepo
        .findById(ctx.turnId())
        .ifPresent(
            turn -> {
              if ("STREAMING".equals(turn.getStatus())) {
                turn.setStatus("CANCELLED");
                turn.setAssistantSummary(
                    AgentText.firstNonBlank("Stream disconnected.", turn.getAssistantSummary()));
                turnRepo.save(turn);
              }
            });
    eventRecorder.append(
        ctx.sessionId(),
        ctx.turnId(),
        "STREAM_DISCONNECTED",
        Map.of("message", "client disconnected"));
  }
}
