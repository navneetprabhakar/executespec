package com.navneet.executespec.landing;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;

import com.navneet.executespec.report.ValidationReportRepository;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * Selling-point endpoints for the public marketing surface.
 *
 * <p>Three routes, all read-only, all permitted without auth via {@link
 * com.navneet.executespec.auth.security.SecurityConfig}. They publish aggregate counters and
 * per-run metrics (score, file count, duration, whether a PR opened) plus letter-substituted
 * project labels. They deliberately publish <strong>no customer specification text</strong> — see
 * {@link #redactedSpecLabel()} for why the previous "anonymized" claim did not hold:
 *
 * <ul>
 *   <li>{@code GET /api/v3/landing/stats} — counter pair (specs this week + verified-score
 *       percent). Drives the hero proof number.
 *   <li>{@code GET /api/v3/landing/sample-runs} — gallery of 6 recent successful runs (verified
 *       score + file count + duration; {@code spec} is always empty). Drives the "see what we
 *       built" proof block.
 *   <li>{@code GET /api/v3/public/exec-snapshot} — the full /exec page: totals, spend by
 *       letter-labelled project, and top wins by score ({@code spec} always empty). Drives the
 *       public share-link route for PgM/CFO persona ("Watch this team's last 30 days").
 * </ul>
 *
 * Conservative caching strategy: nothing here changes faster than minutely even at our scale, but
 * caching ships in a follow-up — for now we let the native queries run on every hit. Indexes on
 * {@code validation_report.created_at} + {@code pipeline_run.project_id} keep the hot path under 50
 * ms.
 */
@RestController
@RequestMapping("/api/v3")
@RequiredArgsConstructor
public class LandingController {

  private static final int GALLERY_LIMIT = 6;
  private static final int EXEC_WINDOW_DAYS = 30;
  private static final DateTimeFormatter WHEN_FMT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(java.time.ZoneOffset.UTC);

  private final ValidationReportRepository reportRepo;

  @GetMapping("/landing/stats")
  public ResponseEntity<LandingStats> stats() {
    Instant weekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
    long thisWeek = reportRepo.countCompletedSince(weekAgo);
    Integer verifiedPct = reportRepo.verifiedScorePercentSince(weekAgo);
    long allTime = reportRepo.countAllTime();
    return ResponseEntity.ok(new LandingStats(thisWeek, verifiedPct, allTime));
  }

  @GetMapping("/landing/sample-runs")
  public ResponseEntity<List<SampleRun>> sampleRuns() {
    List<Object[]> rows = reportRepo.findTopGalleryRuns(GALLERY_LIMIT);
    List<SampleRun> out =
        rows.stream()
            .map(
                r -> {
                  String spec = redactedSpecLabel();
                  Integer score = toInt(r[1]);
                  Integer files = toInt(r[2]);
                  Long durationMs = computeDurationMs(r[3], r[4]);
                  boolean prOpened = r[5] != null && !((String) r[5]).isBlank();
                  String verdict =
                      score != null && score >= 90
                          ? "READY"
                          : score != null && score >= 70 ? "NEEDS_REVIEW" : "FAILED";
                  return new SampleRun(spec, verdict, score, files, durationMs, prOpened);
                })
            .toList();
    return ResponseEntity.ok(out);
  }

  @GetMapping("/public/exec-snapshot")
  public ResponseEntity<ExecSnapshot> execSnapshot() {
    Instant since = Instant.now().minus(EXEC_WINDOW_DAYS, ChronoUnit.DAYS);
    List<Object[]> totalsList = reportRepo.execSnapshotTotals(since);
    if (totalsList == null || totalsList.isEmpty()) {
      return ResponseEntity.ok(
          new ExecSnapshot(
              EXEC_WINDOW_DAYS,
              0L,
              null,
              null,
              0L,
              0L,
              Collections.emptyList(),
              Collections.emptyList()));
    }
    Object[] totals = totalsList.get(0);

    long totalRuns = toLong(totals[0]);
    Integer successPct = toInt(totals[1]);
    long activeProjects = toLong(totals[3]);
    long needsAttention = toLong(totals[4]);

    // Anonymize project ids → "Project A", "Project B", …
    List<Object[]> spendRows = reportRepo.spendByProjectSince(since);
    List<ExecSnapshot.ProjectSpend> spendByProject = new java.util.ArrayList<>();
    for (int i = 0; i < spendRows.size(); i++) {
      Object[] row = spendRows.get(i);
      String letter = "Project " + (char) ('A' + i);
      // RR-563: raw provider-dollar spend is neutralized on this UNAUTHENTICATED public surface.
      spendByProject.add(new ExecSnapshot.ProjectSpend(letter, toLong(row[1]), redactedSpendUsd()));
    }

    List<Object[]> winRows = reportRepo.topWinsSince(since);
    List<ExecSnapshot.TopWin> topWins =
        winRows.stream()
            .map(
                row ->
                    new ExecSnapshot.TopWin(
                        redactedSpecLabel(), toInt(row[1]), redactedSpendUsd(), formatWhen(row[3])))
            .toList();

    return ResponseEntity.ok(
        new ExecSnapshot(
            EXEC_WINDOW_DAYS,
            totalRuns,
            successPct,
            // RR-563: totalSpendUsd is raw provider COGS. It was published on an unauthenticated
            // marketing endpoint (no principal to role-gate against), which leaked internal
            // aggregate
            // spend plus per-anonymized-project and per-win dollar figures. The DTO shape is kept
            // so
            // the marketing page keeps rendering; the dollar figures are simply withheld (null).
            redactedSpendUsd(),
            activeProjects,
            needsAttention,
            spendByProject,
            topWins));
  }

  /**
   * RR-563 — public marketing surfaces publish NO raw provider-dollar cost. Same rationale as
   * {@link #redactedSpecLabel()}: the endpoint is unauthenticated, so there is no viewer role to
   * gate on and the only correct answer is to withhold the figure. Kept as {@code null} rather than
   * {@code 0.0} so a client can distinguish "withheld" from a genuine zero.
   */
  private static Double redactedSpendUsd() {
    return null;
  }

  /**
   * Public marketing surfaces publish NO customer specification text (RR-669/RR-1662 family).
   *
   * <p>These three routes are unauthenticated and were documented as "anonymized", but anonymity
   * was implemented as Markdown-stripping plus truncation to 80-90 characters. That makes a leak
   * smaller, not anonymous: the opening line of a real specification routinely carries the product
   * name, the customer's company, a ticket id, an incident description, a repository URL or an
   * email address — and occasionally a pasted credential. No consent was ever recorded, and every
   * successful private run was eligible purely by having a high score.
   *
   * <p>The correct surface is an explicitly opted-in, separately curated showcase with a review
   * lifecycle and an immutable redacted copy, defaulting every tenant to excluded. Until that
   * exists, these endpoints publish a neutral placeholder. The DTO shape is deliberately unchanged
   * so the marketing page keeps rendering; it will simply show no spec prose.
   *
   * <p>Do not "improve" this by sanitising the customer string harder. Redaction of free-form human
   * prose is not a solvable filtering problem, which is why the previous attempt failed.
   */
  private static String redactedSpecLabel() {
    return "";
  }

  private static Integer toInt(Object o) {
    if (o == null) return null;
    if (o instanceof Number n) return n.intValue();
    return null;
  }

  private static long toLong(Object o) {
    if (o == null) return 0L;
    if (o instanceof Number n) return n.longValue();
    return 0L;
  }

  private static Long computeDurationMs(Object startedAt, Object completedAt) {
    if (startedAt == null || completedAt == null) return null;
    try {
      Instant start = ((Timestamp) startedAt).toInstant();
      Instant end = ((Timestamp) completedAt).toInstant();
      return Duration.between(start, end).toMillis();
    } catch (Exception e) {
      return null;
    }
  }

  private static String formatWhen(Object timestamp) {
    if (timestamp == null) return "";
    try {
      return WHEN_FMT.format(((Timestamp) timestamp).toInstant());
    } catch (Exception e) {
      return "";
    }
  }
}
