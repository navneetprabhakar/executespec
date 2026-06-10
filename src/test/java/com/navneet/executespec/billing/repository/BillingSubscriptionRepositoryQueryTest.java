package com.navneet.executespec.billing.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class BillingSubscriptionRepositoryQueryTest {

  @Test
  void activeSubscriptionQueriesIncludePeriodEndCancellationStatus() throws Exception {
    assertQueryContainsStatus("findActiveByUserId", "CANCELLING_AT_PERIOD_END", String.class);
    assertQueryContainsStatus("findAllActiveOrPastDue", "CANCELLING_AT_PERIOD_END");
    assertQueryContainsStatus("findActiveByTier", "CANCELLING_AT_PERIOD_END", String.class);
    assertQueryContainsStatus("findActiveByOrgId", "CANCELLING_AT_PERIOD_END", String.class);
  }

  @Test
  void periodEndDowngradeQueryIncludesSunsettingCancellationStatus() throws Exception {
    assertQueryContainsStatus(
        "findCancelledPastPeriodEnd", "CANCELLING_AT_PERIOD_END", Instant.class);
  }

  private void assertQueryContainsStatus(String methodName, String status, Class<?>... parameters)
      throws Exception {
    Query query =
        BillingSubscriptionRepository.class
            .getMethod(methodName, parameters)
            .getAnnotation(Query.class);

    assertThat(query.value()).contains(status);
  }
}
