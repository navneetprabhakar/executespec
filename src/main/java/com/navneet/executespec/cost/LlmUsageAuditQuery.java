package com.navneet.executespec.cost;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** Validated, tenant-calendar query window for the Enterprise LLM attempt report. */
public record LlmUsageAuditQuery(
    String orgId,
    LocalDate fromDate,
    LocalDate toDate,
    String timezone,
    Instant fromInclusive,
    Instant toExclusive,
    int page,
    int size) {

  public static final int MAX_WINDOW_DAYS = 366;
  public static final int MAX_PAGE_SIZE = 500;
  public static final int MAX_PAGE = 100_000;
  public static final int GROUP_ROW_LIMIT = 500;
  public static final int CSV_ROW_LIMIT = 10_000;

  public static LlmUsageAuditQuery of(
      String orgId, LocalDate fromDate, LocalDate toDate, String timezone, int page, int size) {
    String normalizedOrg = requireText(orgId, "orgId", 64);
    if (fromDate == null) throw new IllegalArgumentException("from is required");
    if (toDate == null) throw new IllegalArgumentException("to is required");
    if (toDate.isBefore(fromDate)) {
      throw new IllegalArgumentException("to must be on or after from");
    }
    long inclusiveDays = ChronoUnit.DAYS.between(fromDate, toDate) + 1;
    if (inclusiveDays > MAX_WINDOW_DAYS) {
      throw new IllegalArgumentException(
          "date range must be at most " + MAX_WINDOW_DAYS + " calendar days");
    }
    if (page < 0 || page > MAX_PAGE) {
      throw new IllegalArgumentException("page must be between 0 and " + MAX_PAGE);
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
    }

    String normalizedTimezone = requireText(timezone, "timezone", 64);
    final ZoneId zone;
    try {
      zone = ZoneId.of(normalizedTimezone);
    } catch (DateTimeException invalid) {
      throw new IllegalArgumentException("timezone must be a valid IANA zone", invalid);
    }
    final Instant fromInclusive;
    final Instant toExclusive;
    try {
      fromInclusive = fromDate.atStartOfDay(zone).toInstant();
      toExclusive = toDate.plusDays(1).atStartOfDay(zone).toInstant();
    } catch (DateTimeException invalidDate) {
      throw new IllegalArgumentException("date range cannot be converted in timezone", invalidDate);
    }
    return new LlmUsageAuditQuery(
        normalizedOrg, fromDate, toDate, zone.getId(), fromInclusive, toExclusive, page, size);
  }

  public int offset() {
    return Math.multiplyExact(page, size);
  }

  private static String requireText(String value, String field, int maxLength) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(field + " is required");
    String normalized = value.trim();
    if (normalized.length() > maxLength) {
      throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
    }
    return normalized;
  }
}
