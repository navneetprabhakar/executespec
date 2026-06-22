package com.navneet.executespec.integration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.navneet.executespec.billing.service.AnalyticsService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Regression for the 2026-06-23 QA finding (F1): {@link AnalyticsService} queried {@code
 * pipeline_run.created_at}, which does not exist — the table uses {@code started_at}/{@code
 * completed_at}. That raised {@code column "created_at" does not exist} → {@code
 * BadSqlGrammarException} → HTTP 500 on {@code /api/admin/analytics/usage} for every tier, blanking
 * the billing usage panel.
 *
 * <p>The pre-existing {@code AdminAnalyticsControllerTest} mocks {@code AnalyticsService}, so it never
 * ran the SQL and the bug shipped. This exercises the real queries against a real, Flyway-migrated
 * Postgres schema so any column/schema drift fails the build instead of reaching billing. Run with:
 * {@code mvn test -Dgroups=integration}.
 */
class AnalyticsUsageMetricsIntegrationTest extends PostgresIntegrationBase {

  @Autowired AnalyticsService analyticsService;

  @Test
  void usageMetricsExecuteAgainstTheRealSchemaForBothEntityTypes() {
    // No seeded rows needed: the defect was a SQL/schema grammar error, raised regardless of data.
    var org = assertDoesNotThrow(() -> analyticsService.getUsageMetrics("ORG", "org-absent", 30));
    assertNotNull(org);
    assertEquals(0, org.totalRuns());
    assertNotNull(org.runsByDay());

    var user = assertDoesNotThrow(() -> analyticsService.getUsageMetrics("USER", "user-absent", 30));
    assertNotNull(user);
    assertEquals(0, user.totalRuns());

    // getMemberUsage shares the same pipeline_run timestamp column (MAX(r.started_at)).
    assertDoesNotThrow(() -> analyticsService.getMemberUsage("org-absent", 30));
  }
}
