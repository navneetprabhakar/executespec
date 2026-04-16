package com.navneet.executespec.execution.service;

import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.entity.ExecutionSolution;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * v2.5.8 — one-time backfill of {@link ExecutionRun#getTotalActualTokens()} and
 * {@link ExecutionRun#getTotalCostUsd()} for rows that completed BEFORE the
 * {@code SolutionExecutionService.accumulateExecutionRunTotals} fix landed.
 *
 * <p>Previously, finished Launches rendered as "— / —" on the project dashboard
 * because no path summed their child solutions' tokens + cost back up to the
 * parent {@code ExecutionRun}. This job runs once on application startup and
 * repairs the aggregation for historical rows so the dashboard is meaningful
 * for existing tenants, not just new ones.
 *
 * <p>Disabled by default when {@code executespec.backfill.exec-totals.enabled=false}
 * (e.g. on replica / read-only environments).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExecutionTotalsBackfillJob {

    @Value("${executespec.backfill.exec-totals.enabled:true}")
    private boolean enabled;

    private final ExecutionRunRepository runRepo;
    private final ExecutionSolutionRepository solutionRepo;

    @PostConstruct
    @Transactional
    public void backfill() {
        if (!enabled) {
            log.info("ExecutionTotalsBackfillJob skipped — disabled via property.");
            return;
        }
        List<ExecutionRun> candidates = runRepo.findCompletedWithZeroTotals();
        if (candidates.isEmpty()) {
            log.info("ExecutionTotalsBackfillJob: no rows need backfill.");
            return;
        }
        int updated = 0;
        long totalTokensRepaired = 0;
        double totalCostRepaired = 0;
        for (ExecutionRun run : candidates) {
            List<ExecutionSolution> sols = solutionRepo.findByExecutionRun_IdOrderBySequenceNumberAsc(run.getId());
            long tokens = 0;
            BigDecimal cost = BigDecimal.ZERO;
            for (ExecutionSolution s : sols) {
                if (s.getActualTokens() != null) tokens += s.getActualTokens();
                if (s.getCostUsd() != null) cost = cost.add(s.getCostUsd());
            }
            if (tokens == 0 && cost.signum() == 0) continue;  // nothing to repair
            run.setTotalActualTokens(tokens);
            run.setTotalCostUsd(cost);
            runRepo.save(run);
            updated++;
            totalTokensRepaired += tokens;
            totalCostRepaired += cost.doubleValue();
        }
        log.info("ExecutionTotalsBackfillJob: repaired {} ExecutionRun rows; total tokens={}, total cost=${}",
                updated, totalTokensRepaired, String.format("%.2f", totalCostRepaired));
    }
}
