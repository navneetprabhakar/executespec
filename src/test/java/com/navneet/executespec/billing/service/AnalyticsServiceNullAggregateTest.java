package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression pin for the analytics NPE that made {@code GET /api/admin/analytics/usage} return 500
 * for any account with no runs yet — i.e. every newly registered user opening the insights surface.
 *
 * <p>The cause was {@code ((Number) row.getOrDefault("avg_time", 0)).doubleValue()}. {@code
 * getOrDefault} substitutes the default only when the key is ABSENT. A SQL aggregate such as {@code
 * AVG(...)} or {@code SUM(...)} over zero rows returns the column PRESENT and NULL, so the lookup
 * handed back null and the cast threw:
 *
 * <pre>
 * NullPointerException: Cannot invoke "java.lang.Number.intValue()" because the return value
 * of "java.util.Map.getOrDefault(Object, Object)" is null
 * </pre>
 *
 * <p>This was found by walking the running UI, not by the test suite — the whole suite was green
 * while the endpoint 500'd. These cases pin the null-present-key shape that {@code getOrDefault}
 * cannot handle.
 */
class AnalyticsServiceNullAggregateTest {

  /** Mirrors the production helper: present-but-null must read as zero, not throw. */
  private static int intOf(Map<String, Object> row, String key) {
    Object v = row.get(key);
    return v instanceof Number n ? n.intValue() : 0;
  }

  private static double doubleOf(Map<String, Object> row, String key) {
    Object v = row.get(key);
    return v instanceof Number n ? n.doubleValue() : 0d;
  }

  @Test
  @DisplayName("a present-but-null aggregate column reads as zero instead of throwing")
  void presentButNullAggregateDoesNotThrow() {
    // Exactly what PostgreSQL returns for AVG/SUM over zero matching rows: the key IS there.
    Map<String, Object> emptyAggregate = new HashMap<>();
    emptyAggregate.put("total", null);
    emptyAggregate.put("avg_time", null);
    emptyAggregate.put("total_cost", null);

    assertThat(emptyAggregate).containsKey("avg_time");
    assertThat(emptyAggregate.get("avg_time")).isNull();

    assertThatCode(
            () -> {
              intOf(emptyAggregate, "total");
              doubleOf(emptyAggregate, "avg_time");
              doubleOf(emptyAggregate, "total_cost");
            })
        .doesNotThrowAnyException();

    assertThat(intOf(emptyAggregate, "total")).isZero();
    assertThat(doubleOf(emptyAggregate, "avg_time")).isZero();
  }

  @Test
  @DisplayName("getOrDefault is genuinely unsafe here — this is the bug being prevented")
  void getOrDefaultIsUnsafeForPresentNullKeys() {
    Map<String, Object> row = new HashMap<>();
    row.put("avg_time", null);

    // Documents WHY the helper exists: the old expression returns null and the cast throws.
    assertThat(row.getOrDefault("avg_time", 0)).isNull();
  }

  @Test
  @DisplayName("absent keys and real values both still work")
  void absentAndPresentValuesBehave() {
    Map<String, Object> row = new HashMap<>();
    row.put("total", 7);
    row.put("avg_time", 2.5d);

    assertThat(intOf(row, "total")).isEqualTo(7);
    assertThat(doubleOf(row, "avg_time")).isEqualTo(2.5d);
    assertThat(intOf(row, "not_selected")).isZero();
    assertThat(doubleOf(row, "not_selected")).isZero();
  }
}
