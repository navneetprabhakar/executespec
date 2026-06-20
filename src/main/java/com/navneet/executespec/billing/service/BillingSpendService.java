package com.navneet.executespec.billing.service;

import java.time.Instant;

import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class BillingSpendService {

  private final PipelineRunRepository pipelineRunRepository;
  private final ExecutionRunRepository executionRunRepository;

  public double spentUsdSince(String userId, Instant periodStart) {
    return pipelineRunSpend(userId, periodStart) + executionRunSpend(userId, periodStart);
  }

  private double pipelineRunSpend(String userId, Instant periodStart) {
    try {
      Double value = pipelineRunRepository.sumActualCostByUserAndSince(userId, periodStart);
      return value == null ? 0.0 : value;
    } catch (Exception e) {
      log.warn(
          "Unable to aggregate pipeline-run billing spend for userId={} since={}: {}",
          userId,
          periodStart,
          e.getMessage());
      return 0.0;
    }
  }

  private double executionRunSpend(String userId, Instant periodStart) {
    try {
      Double value = executionRunRepository.sumTotalCostByUserAndSince(userId, periodStart);
      return value == null ? 0.0 : value;
    } catch (Exception e) {
      log.warn(
          "Unable to aggregate execution-run billing spend for userId={} since={}: {}",
          userId,
          periodStart,
          e.getMessage());
      return 0.0;
    }
  }
}
