package com.navneet.executespec.cost;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class RunStepUsageRecorder {

  private final RunStepUsageRepository repository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(
      String runId, String taskId, String provider, String model, RunCostTracker.CallUsage usage) {
    if (runId == null || runId.isBlank() || usage == null) return;
    try {
      repository.save(
          RunStepUsage.builder()
              .id(UUID.randomUUID().toString())
              .runId(runId)
              .taskId(blankToNull(taskId))
              .provider(firstNonBlank(provider, usage.provider()))
              .model(firstNonBlank(model, usage.model()))
              .inputTokens(usage.inputTokens())
              .outputTokens(usage.outputTokens())
              .cacheWriteTokens(usage.cacheWriteTokens())
              .cacheReadTokens(usage.cacheReadTokens())
              .reasoningTokens(usage.reasoningTokens())
              .costUsd(usage.costUsd())
              .callIndex(usage.callIndex())
              .createdAt(Instant.now())
              .build());
    } catch (Exception e) {
      log.debug("run_step_usage write failed run={} task={}: {}", runId, taskId, e.getMessage());
    }
  }

  private static String firstNonBlank(String primary, String fallback) {
    return primary == null || primary.isBlank() ? blankToNull(fallback) : primary;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
