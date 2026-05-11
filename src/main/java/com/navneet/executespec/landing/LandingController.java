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
 * <p>Three routes, all read-only, all anonymized, all permitted without auth via {@link
 * com.navneet.executespec.auth.security.SecurityConfig}:
 *
 * <ul>
 *   <li>{@code GET /api/v3/landing/stats} — counter pair (specs this week + verified-score
 *       percent). Drives the hero proof number.
 *   <li>{@code GET /api/v3/landing/sample-runs} — gallery of 6 recent successful runs (anonymized
 *       spec text + verified score + duration). Drives the "see what we built" proof block.
 *   <li>{@code GET /api/v3/public/exec-snapshot} — the full /exec page, anonymized. Drives the
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
  private static final int SPEC_TRUNCATION = 90;
  private static final int EXEC_SPEC_TRUNCATION = 80;
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
                  String spec = truncate((String) r[0], SPEC_TRUNCATION);
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
              0.0,
              0L,
              0L,
              Collections.emptyList(),
              Collections.emptyList()));
    }
    Object[] totals = totalsList.get(0);

    long totalRuns = toLong(totals[0]);
    Integer successPct = toInt(totals[1]);
    Double totalSpend = toDouble(totals[2]);
    long activeProjects = toLong(totals[3]);
    long needsAttention = toLong(totals[4]);

    // Anonymize project ids → "Project A", "Project B", …
    List<Object[]> spendRows = reportRepo.spendByProjectSince(since);
    List<ExecSnapshot.ProjectSpend> spendByProject = new java.util.ArrayList<>();
    for (int i = 0; i < spendRows.size(); i++) {
      Object[] row = spendRows.get(i);
      String letter = "Project " + (char) ('A' + i);
      spendByProject.add(new ExecSnapshot.ProjectSpend(letter, toLong(row[1]), toDouble(row[2])));
    }

    List<Object[]> winRows = reportRepo.topWinsSince(since);
    List<ExecSnapshot.TopWin> topWins =
        winRows.stream()
            .map(
                row ->
                    new ExecSnapshot.TopWin(
                        truncate((String) row[0], EXEC_SPEC_TRUNCATION),
                        toInt(row[1]),
                        toDouble(row[2]),
                        formatWhen(row[3])))
            .toList();

    return ResponseEntity.ok(
        new ExecSnapshot(
            EXEC_WINDOW_DAYS,
            totalRuns,
            successPct,
            totalSpend,
            activeProjects,
            needsAttention,
            spendByProject,
            topWins));
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  /**
   * Sanitize a raw user-submitted spec into a single line of plain text safe for the public
   * landing/exec-snapshot surface, then truncate.
   *
   * <p>The 2026-05-11 audit found {@code topWins[].spec} entries on {@code
   * /api/v3/public/exec-snapshot} leaking raw markdown ({@code "** **Project:** Single-Player 3D
   * Race Game ..."}) — the previous {@link #truncate(String, int)} helper only collapsed
   * whitespace. We now strip the common markdown noise (heading markers, bold/italic stars +
   * underscores, inline code backticks, list bullets, blockquote arrows) before the whitespace
   * collapse, so prospects see "Project: Single-Player 3D Race Game …" instead of leaked stars.
   * Functional shape (single-line truncated string) unchanged; this strictly reduces the surface
   * area, never widens it.
   */
  private static String truncate(String s, int max) {
    if (s == null) return "";
    String cleaned =
        s
            // Heading markers at line start (e.g. "## " / "### ")
            .replaceAll("(?m)^#{1,6}\\s+", "")
            // List bullets at line start (* / - / +)
            .replaceAll("(?m)^\\s*[*\\-+]\\s+", "")
            // Numbered list bullets at line start (1. / 2) / etc.)
            .replaceAll("(?m)^\\s*\\d+[.)]\\s+", "")
            // Blockquote markers
            .replaceAll("(?m)^>\\s+", "")
            // Bold (**…**) — drop just the markers
            .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
            // Italic (*…* or _…_) — drop just the markers
            .replaceAll("(?<!\\w)\\*([^*\\n]+)\\*(?!\\w)", "$1")
            .replaceAll("(?<!\\w)_([^_\\n]+)_(?!\\w)", "$1")
            // Inline code backticks
            .replace("`", "")
            // Stray markdown stars left after the above (e.g. orphan "**")
            .replace("**", "")
            // Trailing/leading whitespace runs + newlines → single space
            .replaceAll("\\s+", " ")
            .trim();
    return cleaned.length() <= max ? cleaned : cleaned.substring(0, max - 1) + "…";
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

  private static Double toDouble(Object o) {
    if (o == null) return null;
    if (o instanceof Number n) return n.doubleValue();
    return null;
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
