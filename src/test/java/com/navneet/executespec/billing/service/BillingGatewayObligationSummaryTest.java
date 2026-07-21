package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.billing.entity.BillingGatewayObligation;
import com.navneet.executespec.billing.repository.BillingGatewayObligationRepository;

import org.junit.jupiter.api.Test;

/**
 * RR-1681 — the admin visibility half. The summary must separate still-retrying failures from
 * dead-lettered ones (attempts exhausted) and from operator-only UNSUPPORTED rows, per kind, so a
 * stuck obligation is not hidden inside a single "failed" count.
 */
class BillingGatewayObligationSummaryTest {

  private final BillingGatewayObligationRepository repo =
      mock(BillingGatewayObligationRepository.class);
  private final BillingGatewayObligationService service = new BillingGatewayObligationService(repo);

  private BillingGatewayObligation row(String kind, String status, int attempts) {
    return BillingGatewayObligation.builder()
        .id(UUID.randomUUID())
        .kind(kind)
        .status(status)
        .attempts(attempts)
        .createdAt(Instant.now())
        .updatedAt(Instant.now())
        .build();
  }

  @Test
  void summarySeparatesPendingFailedDeadLetteredAndUnsupportedPerKind() {
    when(repo.findUnresolved())
        .thenReturn(
            List.of(
                row(
                    BillingGatewayObligation.KIND_PRORATED_REFUND,
                    BillingGatewayObligation.STATUS_PENDING,
                    0),
                row(
                    BillingGatewayObligation.KIND_PRORATED_REFUND,
                    BillingGatewayObligation.STATUS_FAILED,
                    2),
                row(
                    BillingGatewayObligation.KIND_PRORATED_REFUND,
                    BillingGatewayObligation.STATUS_FAILED,
                    BillingGatewayObligationService.MAX_ATTEMPTS),
                row(
                    BillingGatewayObligation.KIND_CANCEL_OLD_SUBSCRIPTION,
                    BillingGatewayObligation.STATUS_UNSUPPORTED,
                    3)));

    var summary = service.unresolvedSummary();

    assertThat(summary.total()).isEqualTo(4);
    assertThat(summary.pending()).isEqualTo(1);
    assertThat(summary.failedRetrying()).isEqualTo(1);
    assertThat(summary.deadLettered()).isEqualTo(1);
    assertThat(summary.unsupported()).isEqualTo(1);

    var refund =
        summary.byKind().stream()
            .filter(k -> k.kind().equals(BillingGatewayObligation.KIND_PRORATED_REFUND))
            .findFirst()
            .orElseThrow();
    assertThat(refund.pending()).isEqualTo(1);
    assertThat(refund.failedRetrying()).isEqualTo(1);
    assertThat(refund.deadLettered()).isEqualTo(1);
    assertThat(refund.unsupported()).isEqualTo(0);
  }

  @Test
  void emptyLedgerSummarisesToZero() {
    when(repo.findUnresolved()).thenReturn(List.of());
    var summary = service.unresolvedSummary();
    assertThat(summary.total()).isZero();
    assertThat(summary.byKind()).isEmpty();
  }
}
