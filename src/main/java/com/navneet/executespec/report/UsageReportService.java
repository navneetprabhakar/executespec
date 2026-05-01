package com.navneet.executespec.report;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.service.MailService;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Collects usage data for all users and orgs, then dispatches report emails via MailService.
 * Triggered manually by SUPER_ADMIN from the Admin Panel.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UsageReportService {

  private final AppUserRepository userRepo;
  private final OrgRepository orgRepo;
  private final ProjectRepository projectRepo;
  private final PipelineRunRepository runRepo;
  private final MailService mailService;

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy");

  // ── Public DTOs ─────────────────────────────────────────────────────────────

  public record UserUsageData(
      int totalRuns,
      int completedRuns,
      int failedRuns,
      long totalInputTokens,
      long totalOutputTokens,
      double estimatedCostUsd,
      int prevTotalRuns,
      long prevTotalTokens,
      double prevCostUsd,
      List<ProjectBreakdown> projects) {}

  public record OrgUsageData(
      int totalRuns,
      int completedRuns,
      int failedRuns,
      long totalInputTokens,
      long totalOutputTokens,
      double estimatedCostUsd,
      int prevTotalRuns,
      long prevTotalTokens,
      double prevCostUsd,
      List<TopUser> topUsers,
      List<ProjectBreakdown> projects,
      int memberCount) {}

  public record TopUser(String name, String email, int runCount, long tokens) {}

  public record ProjectBreakdown(String projectName, int runs, long tokens) {}

  // ── Trigger ─────────────────────────────────────────────────────────────────

  /**
   * Sends usage reports to all active orgs (→ org admin) and solo users (no org).
   *
   * @param period "WEEKLY" or "MONTHLY"
   * @return number of emails queued
   */
  @Transactional(readOnly = true)
  public int triggerReports(String period) {
    var range = calculateDateRange(period);
    var prevRange = calculatePreviousRange(period, range);
    String periodLabel = "WEEKLY".equals(period) ? "Weekly" : "Monthly";
    String periodRange = formatRange(range.start, range.end);

    int emailCount = 0;

    // 1. Org reports → org admin email
    for (Org org : orgRepo.findAllActive()) {
      if (org.getAdminEmail() == null || org.getAdminEmail().isBlank()) {
        log.warn("Skipping org {} — no admin email", org.getId());
        continue;
      }
      try {
        OrgUsageData data = collectOrgUsage(org.getId(), range, prevRange);
        mailService.sendOrgUsageReport(
            org.getAdminEmail(), org.getName(), periodLabel, periodRange, data);
        emailCount++;
      } catch (Exception e) {
        log.warn("Failed to send org usage report for {}: {}", org.getName(), e.getMessage());
      }
    }

    // 2. Solo users (no org)
    for (AppUser user : userRepo.findActiveSoloUsers()) {
      try {
        UserUsageData data = collectUserUsage(user.getId(), range, prevRange);
        String firstName = user.getFirstName() != null ? user.getFirstName() : "there";
        mailService.sendUserUsageReport(user.getEmail(), firstName, periodLabel, periodRange, data);
        emailCount++;
      } catch (Exception e) {
        log.warn("Failed to send user usage report for {}: {}", user.getEmail(), e.getMessage());
      }
    }

    log.info("Usage report trigger complete: period={}, emailsSent={}", period, emailCount);
    return emailCount;
  }

  // ── Data collection ─────────────────────────────────────────────────────────

  private OrgUsageData collectOrgUsage(String orgId, DateRange range, DateRange prev) {
    int total = (int) runRepo.countByOrgIdAndStartedAtBetween(orgId, range.start, range.end);
    int completed =
        (int)
            runRepo.countByOrgIdAndStatusAndStartedAtBetween(
                orgId, "COMPLETE", range.start, range.end);
    int failed =
        (int)
            runRepo.countByOrgIdAndStatusAndStartedAtBetween(
                orgId, "FAILED", range.start, range.end);
    long inTok = runRepo.sumInputTokensByOrgAndDateRange(orgId, range.start, range.end);
    long outTok = runRepo.sumOutputTokensByOrgAndDateRange(orgId, range.start, range.end);

    int prevTotal = (int) runRepo.countByOrgIdAndStartedAtBetween(orgId, prev.start, prev.end);
    long prevInTok = runRepo.sumInputTokensByOrgAndDateRange(orgId, prev.start, prev.end);
    long prevOutTok = runRepo.sumOutputTokensByOrgAndDateRange(orgId, prev.start, prev.end);

    double cost = estimateCost(inTok, outTok);
    double prevCost = estimateCost(prevInTok, prevOutTok);

    // Top 5 users
    List<TopUser> topUsers = new ArrayList<>();
    for (Object[] row :
        runRepo.topUsersByRunCount(orgId, range.start, range.end, PageRequest.of(0, 5))) {
      String userId = (String) row[0];
      int runs = ((Number) row[1]).intValue();
      long tokens = ((Number) row[2]).longValue();
      String name =
          userRepo
              .findById(userId)
              .map(
                  u ->
                      ((u.getFirstName() != null ? u.getFirstName() : "")
                              + " "
                              + (u.getLastName() != null ? u.getLastName() : ""))
                          .trim())
              .orElse("Unknown");
      String email = userRepo.findById(userId).map(AppUser::getEmail).orElse("");
      topUsers.add(new TopUser(name, email, runs, tokens));
    }

    // Per-project
    List<ProjectBreakdown> projects =
        resolveProjects(runRepo.projectBreakdownByOrg(orgId, range.start, range.end));

    int memberCount = userRepo.findActiveByOrgId(orgId).size();

    return new OrgUsageData(
        total,
        completed,
        failed,
        inTok,
        outTok,
        cost,
        prevTotal,
        prevInTok + prevOutTok,
        prevCost,
        topUsers,
        projects,
        memberCount);
  }

  private UserUsageData collectUserUsage(String userId, DateRange range, DateRange prev) {
    int total =
        (int) runRepo.countByCreatedByUserIdAndStartedAtBetween(userId, range.start, range.end);
    int completed =
        (int)
            runRepo.countByCreatedByUserIdAndStatusAndStartedAtBetween(
                userId, "COMPLETE", range.start, range.end);
    int failed =
        (int)
            runRepo.countByCreatedByUserIdAndStatusAndStartedAtBetween(
                userId, "FAILED", range.start, range.end);
    long inTok = runRepo.sumInputTokensByUserAndDateRange(userId, range.start, range.end);
    long outTok = runRepo.sumOutputTokensByUserAndDateRange(userId, range.start, range.end);

    int prevTotal =
        (int) runRepo.countByCreatedByUserIdAndStartedAtBetween(userId, prev.start, prev.end);
    long prevInTok = runRepo.sumInputTokensByUserAndDateRange(userId, prev.start, prev.end);
    long prevOutTok = runRepo.sumOutputTokensByUserAndDateRange(userId, prev.start, prev.end);

    double cost = estimateCost(inTok, outTok);
    double prevCost = estimateCost(prevInTok, prevOutTok);

    List<ProjectBreakdown> projects =
        resolveProjects(runRepo.projectBreakdownByUser(userId, range.start, range.end));

    return new UserUsageData(
        total,
        completed,
        failed,
        inTok,
        outTok,
        cost,
        prevTotal,
        prevInTok + prevOutTok,
        prevCost,
        projects);
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private record DateRange(Instant start, Instant end) {}

  private DateRange calculateDateRange(String period) {
    LocalDate now = LocalDate.now(IST);
    if ("WEEKLY".equals(period)) {
      LocalDate end = now;
      LocalDate start = end.minusDays(7);
      return new DateRange(start.atStartOfDay(IST).toInstant(), end.atStartOfDay(IST).toInstant());
    } else {
      // MONTHLY: last full calendar month
      LocalDate firstOfThisMonth = now.withDayOfMonth(1);
      LocalDate firstOfLastMonth = firstOfThisMonth.minusMonths(1);
      return new DateRange(
          firstOfLastMonth.atStartOfDay(IST).toInstant(),
          firstOfThisMonth.atStartOfDay(IST).toInstant());
    }
  }

  private DateRange calculatePreviousRange(String period, DateRange current) {
    if ("WEEKLY".equals(period)) {
      Duration dur = Duration.between(current.start, current.end);
      return new DateRange(current.start.minus(dur), current.start);
    } else {
      LocalDate start = LocalDate.ofInstant(current.start, IST).minusMonths(1);
      return new DateRange(start.atStartOfDay(IST).toInstant(), current.start);
    }
  }

  private String formatRange(Instant start, Instant end) {
    String s = LocalDate.ofInstant(start, IST).format(DATE_FMT);
    String e = LocalDate.ofInstant(end, IST).minusDays(1).format(DATE_FMT);
    return s + " – " + e;
  }

  /**
   * Rough cost estimate based on blended Haiku pricing (majority of tokens). Input: $0.80/1M
   * tokens, Output: $4.00/1M tokens.
   */
  private double estimateCost(long inputTokens, long outputTokens) {
    return (inputTokens * 0.80 / 1_000_000.0) + (outputTokens * 4.00 / 1_000_000.0);
  }

  private List<ProjectBreakdown> resolveProjects(List<Object[]> rows) {
    List<ProjectBreakdown> result = new ArrayList<>();
    for (Object[] row : rows) {
      String projectId = (String) row[0];
      int runs = ((Number) row[1]).intValue();
      long tokens = ((Number) row[2]).longValue();
      String name = projectRepo.findById(projectId).map(Project::getName).orElse("Unknown Project");
      result.add(new ProjectBreakdown(name, runs, tokens));
    }
    return result;
  }
}
