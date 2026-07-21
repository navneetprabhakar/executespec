package com.navneet.executespec.run;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * RR-1559 — retention sweep for {@code run_submission_idempotency}.
 *
 * <p>{@link RunIdempotencyService#purgeExpired(int)} and its {@code
 * idx_run_submission_idempotency_expiry} index (V303) existed with no caller anywhere in the
 * repository, so the table grew one row per submitted run forever and the documented {@code
 * run.idempotency.ttl-millis} retention was never enforced.
 *
 * <p>The delete is bounded per pass and driven by a {@code DELETE … WHERE id IN (SELECT … LIMIT ?)}
 * that only ever touches rows past {@code expires_at} in a settled (non-{@code CLAIMED}) state, so
 * it is safe to run concurrently on every JVM: a row another node already deleted simply does not
 * match, and an in-flight claim is never reclaimed by this path. The sweep repeats within one pass
 * only while it keeps filling its batch, capped so a large backlog is drained over several runs
 * rather than in one long transaction.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RunSubmissionIdempotencyRetentionJob {

  private final RunIdempotencyService idempotencyService;

  @Value("${run.idempotency.purge-batch-size:500}")
  private int batchSize;

  @Value("${run.idempotency.purge-max-batches:20}")
  private int maxBatches;

  /** Hourly: cheap, indexed, and keeps the backlog from ever becoming a long delete. */
  @Scheduled(cron = "${run.idempotency.purge-cron:0 20 * * * *}")
  public void purgeSettledExpiredClaims() {
    int total = 0;
    for (int batch = 0; batch < Math.max(1, maxBatches); batch++) {
      int deleted;
      try {
        deleted = idempotencyService.purgeExpired(Math.max(1, batchSize));
      } catch (Exception e) {
        // Retention is best-effort maintenance; a failed sweep must not take down the scheduler.
        // Logged rather than swallowed so a persistent failure is visible.
        log.warn("run-submission idempotency retention sweep failed after {} rows", total, e);
        return;
      }
      total += deleted;
      if (deleted < Math.max(1, batchSize)) break;
    }
    if (total > 0) {
      log.info("Purged {} settled expired run-submission idempotency records", total);
    }
  }
}
