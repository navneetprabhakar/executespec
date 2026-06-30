package com.navneet.executespec.billing.service;

import java.time.Instant;
import java.util.UUID;

import com.navneet.executespec.agent.RunKeyContext;
import com.navneet.executespec.billing.config.ManagedAiProperties;
import com.navneet.executespec.billing.dto.ManagedAiDtos.ManagedAiBalanceResponse;
import com.navneet.executespec.billing.dto.ManagedAiDtos.ManagedAiUsageEventResponse;
import com.navneet.executespec.billing.entity.ManagedAiUsageEvent;
import com.navneet.executespec.billing.entity.ManagedAiUsagePeriod;
import com.navneet.executespec.cost.RunCostTracker;

import org.springframework.stereotype.Component;

@Component
class ManagedAiUsageMapper {

  ManagedAiUsageEvent toEvent(
      String userId,
      String orgId,
      ManagedAiUsagePeriod period,
      String runId,
      String taskId,
      String agentRole,
      String stepId,
      String provider,
      String model,
      String modelClass,
      RunCostTracker.CallUsage usage,
      RunKeyContext.Source source,
      long credits,
      String before,
      String after,
      ManagedAiProperties.TierPolicy policy) {
    return ManagedAiUsageEvent.builder()
        .id(UUID.randomUUID().toString())
        .periodId(period.getId())
        .userId(userId)
        .orgId(orgId)
        .runId(runId)
        .agentSessionId(runId != null && runId.startsWith("agent-") ? runId : null)
        .sourceType(sourceType(source))
        .provider(provider)
        .model(model)
        .modelClass(modelClass)
        .agentRole(agentRole)
        .stepId(stepId != null ? stepId : taskId)
        .inputTokens(usage.inputTokens())
        .outputTokens(usage.outputTokens())
        .cacheReadTokens(usage.cacheReadTokens())
        .cacheWriteTokens(usage.cacheWriteTokens())
        .reasoningTokens(usage.reasoningTokens())
        .rawCostUsd(usage.costUsd())
        .retailMultiplier(policy.getRetailMultiplier())
        .aiCreditsCharged(credits)
        .routingStateBefore(before)
        .routingStateAfter(after)
        .createdAt(Instant.now())
        .build();
  }

  ManagedAiBalanceResponse toBalanceResponse(
      ManagedAiUsagePeriod period,
      ManagedAiProperties.TierPolicy policy,
      boolean managed,
      String state) {
    long totalCredits = period.getIncludedAiCredits() + period.getPurchasedAiCredits();
    long remaining = Math.max(0, totalCredits - period.getAiCreditsUsed());
    double hard = policy.effectiveHardStopUsd();
    return new ManagedAiBalanceResponse(
        period.getUserId(),
        period.getOrgId(),
        period.getTier(),
        state,
        period.getPeriodStart(),
        period.getPeriodEnd(),
        period.getIncludedAiCredits(),
        period.getPurchasedAiCredits(),
        period.getAiCreditsUsed(),
        remaining,
        period.getRawCogsCapUsd(),
        period.getRawCogsUsedUsd(),
        hard > 0 ? Math.max(0.0, hard - period.getRawCogsUsedUsd()) : -1.0,
        period.getFrontierRawCapUsd(),
        period.getFrontierRawUsedUsd(),
        period.getFrontierRawCapUsd() > 0
            ? Math.max(0.0, period.getFrontierRawCapUsd() - period.getFrontierRawUsedUsd())
            : -1.0,
        period.getValueModeAfterRawUsd(),
        period.getCheapOnlyAfterRawUsd(),
        period.getHardStopAfterRawUsd(),
        managed,
        policy.getRetailMultiplier());
  }

  ManagedAiUsageEventResponse toEventResponse(ManagedAiUsageEvent e) {
    return new ManagedAiUsageEventResponse(
        e.getId(),
        e.getPeriodId(),
        e.getRunId(),
        e.getAgentSessionId(),
        e.getSourceType(),
        e.getProvider(),
        e.getModel(),
        e.getModelClass(),
        e.getAgentRole(),
        e.getStepId(),
        e.getInputTokens(),
        e.getOutputTokens(),
        e.getCacheReadTokens(),
        e.getCacheWriteTokens(),
        e.getReasoningTokens(),
        e.getRawCostUsd(),
        e.getRetailMultiplier(),
        e.getAiCreditsCharged(),
        e.getRoutingStateBefore(),
        e.getRoutingStateAfter(),
        e.getCreatedAt());
  }

  private static String sourceType(RunKeyContext.Source source) {
    return source == null || source == RunKeyContext.Source.PLATFORM ? "PLATFORM" : "BYOK";
  }
}
