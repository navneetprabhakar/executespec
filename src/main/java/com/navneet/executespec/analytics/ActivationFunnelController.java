package com.navneet.executespec.analytics;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * M-049 (Backlog) — server-side reads of the V125 activation funnel.
 *
 * <p>The {@code user_activation_event} ledger captures each user's first-time event for the 6-step
 * funnel (SIGNUP → PROJECT_CREATED → SPEC_SUBMITTED → PLAN_APPROVED → RUN_COMPLETE → PR_MERGED).
 * PostHog handles the rich cohort/time analytics; this controller exposes the cheap headline counts
 * that the SUPER_ADMIN dashboard cares about so we don't have to bounce to PostHog for the daily
 * check.
 *
 * <p>Single endpoint: {@code GET /api/admin/activation-funnel?sinceDays=30} →
 *
 * <pre>
 *   {
 *     "windowDays": 30,
 *     "windowStart": "2026-03-28T...",
 *     "stages": {
 *       "SIGNUP":          { "total": 142, "windowed": 38 },
 *       "PROJECT_CREATED": { "total":  98, "windowed": 25 },
 *       ...
 *     }
 *   }
 * </pre>
 *
 * <p>The {@code total} is lifetime; {@code windowed} is the last {@code sinceDays}. Conversion-rate
 * computation (e.g. SPEC_SUBMITTED / SIGNUP) is left to the caller — the raw counts are the
 * cleanest contract for an admin chart.
 */
@RestController
@RequestMapping("/api/admin/activation-funnel")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class ActivationFunnelController {

  private final UserActivationEventRepository repo;

  @GetMapping
  public ResponseEntity<FunnelReport> read(
      @RequestParam(required = false, defaultValue = "30") int sinceDays) {
    int safeWindow = Math.max(1, Math.min(365, sinceDays));
    Instant windowStart = Instant.now().minus(safeWindow, ChronoUnit.DAYS);

    // EnumMap-flavoured LinkedHashMap preserves declaration order,
    // which matters because the UI renders the funnel in this order.
    Map<String, StageCounts> stages = new LinkedHashMap<>();
    for (EventName ev : EventName.values()) {
      long total = repo.countByEventName(ev.name());
      long windowed = repo.countByEventNameSince(ev.name(), windowStart);
      stages.put(ev.name(), new StageCounts(total, windowed));
    }
    return ResponseEntity.ok(new FunnelReport(safeWindow, windowStart, stages));
  }

  public record StageCounts(long total, long windowed) {}

  public record FunnelReport(
      int windowDays, Instant windowStart, Map<String, StageCounts> stages) {}
}
