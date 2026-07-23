package com.navneet.executespec.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * RR-1559 — {@code purgeExpired} and its dedicated V303 expiry index existed with no caller, so
 * {@code run_submission_idempotency} grew one row per submitted run forever and the documented
 * 24-hour retention was never enforced.
 */
class RunSubmissionIdempotencyRetentionJobTest {

  private RunSubmissionIdempotencyRetentionJob job(
      RunIdempotencyService service, int batchSize, int maxBatches) {
    var job = new RunSubmissionIdempotencyRetentionJob(service);
    ReflectionTestUtils.setField(job, "batchSize", batchSize);
    ReflectionTestUtils.setField(job, "maxBatches", maxBatches);
    return job;
  }

  @Test
  void theSweepDrainsFullBatchesAndStopsOnAPartialOne() {
    RunIdempotencyService service = mock(RunIdempotencyService.class);
    when(service.purgeExpired(100)).thenReturn(100, 100, 7);

    job(service, 100, 20).purgeSettledExpiredClaims();

    // Third pass came back short, so the backlog is drained and the sweep stops.
    verify(service, times(3)).purgeExpired(100);
  }

  @Test
  void theSweepIsBoundedPerPassSoALargeBacklogNeverBecomesOneLongDelete() {
    RunIdempotencyService service = mock(RunIdempotencyService.class);
    when(service.purgeExpired(anyInt())).thenReturn(50);

    job(service, 50, 3).purgeSettledExpiredClaims();

    verify(service, times(3)).purgeExpired(50);
  }

  @Test
  void aFailedSweepDoesNotPropagateAndTakeDownTheScheduler() {
    RunIdempotencyService service = mock(RunIdempotencyService.class);
    when(service.purgeExpired(anyInt())).thenThrow(new IllegalStateException("db down"));

    var job = job(service, 100, 5);
    assertThat(java.util.Optional.ofNullable(catchThrowable(job))).isEmpty();
    verify(service, times(1)).purgeExpired(100);
  }

  private static Throwable catchThrowable(RunSubmissionIdempotencyRetentionJob job) {
    try {
      job.purgeSettledExpiredClaims();
      return null;
    } catch (Throwable t) {
      return t;
    }
  }
}
