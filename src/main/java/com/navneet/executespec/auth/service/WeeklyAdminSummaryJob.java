package com.navneet.executespec.auth.service;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v2.6.0 — weekly admin summary email.
 *
 * <p>Fires at 09:00 IST every Monday. Aggregates the prior 7-day window:
 * new signups, run throughput + outcomes, active users. Sends a single HTML
 * email to {@code app.mail.admin-address} (default {@code admin@executespec.ai}).
 *
 * <p>Read-only. No state changes. {@link SchedulerLock} prevents duplicate
 * fires across app instances (MIG / Cloud Run).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WeeklyAdminSummaryJob {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter DISPLAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final AppUserRepository userRepo;
    private final PipelineRunRepository pipelineRunRepo;
    private final MailService mailService;

    /**
     * Mondays at 09:00 IST. Window is [now - 7d, now).
     */
    @Scheduled(cron = "0 0 9 * * MON", zone = "Asia/Kolkata")
    @SchedulerLock(name = "weeklyAdminSummary", lockAtLeastFor = "PT2M", lockAtMostFor = "PT10M")
    @Transactional(readOnly = true)
    public void sendWeeklySummary() {
        Instant now = Instant.now();
        Instant since = now.minus(Duration.ofDays(7));

        try {
            Map<String, Object> summary = buildSummary(since, now);
            mailService.sendWeeklyAdminSummary(mailService.getAdminAddress(), summary);
            log.info("[WeeklyAdminSummary] sent to {} · signups={} runs={} completed={}",
                    mailService.getAdminAddress(),
                    summary.get("newSignups"), summary.get("totalRuns"), summary.get("completedRuns"));
        } catch (Exception e) {
            log.error("[WeeklyAdminSummary] failed: {}", e.getMessage(), e);
        }
    }

    Map<String, Object> buildSummary(Instant since, Instant now) {
        Map<String, Object> summary = new HashMap<>();

        // ── New signups ──────────────────────────────────────────────────────
        List<AppUser> allActive = userRepo.findAllActive();
        List<AppUser> newSignups = allActive.stream()
                .filter(u -> u.getCreatedAt() != null && u.getCreatedAt().isAfter(since))
                .sorted(Comparator.comparing(AppUser::getCreatedAt).reversed())
                .toList();

        List<Map<String, String>> signupList = new ArrayList<>();
        for (AppUser u : newSignups.stream().limit(15).toList()) {
            Map<String, String> row = new HashMap<>();
            String firstName = u.getFirstName() == null ? "" : u.getFirstName();
            String lastName = u.getLastName() == null ? "" : u.getLastName();
            row.put("name", (firstName + " " + lastName).trim());
            row.put("email", u.getEmail());
            row.put("tier", u.getTier() == null ? "FREE" : u.getTier());
            row.put("signupMethod", u.getOauthProvider() == null ? "Email"
                    : capitalise(u.getOauthProvider()));
            row.put("createdAt", u.getCreatedAt() == null ? ""
                    : DISPLAY_FMT.format(u.getCreatedAt().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate()));
            signupList.add(row);
        }
        summary.put("newSignups", newSignups.size());
        summary.put("newSignupList", signupList);

        // ── Run throughput + outcomes ────────────────────────────────────────
        long total = pipelineRunRepo.countByStartedAtGreaterThanEqual(since);
        long completed = pipelineRunRepo.countByStatusAndStartedAtGreaterThanEqual("COMPLETE", since);
        long failed = pipelineRunRepo.countByStatusAndStartedAtGreaterThanEqual("FAILED", since);
        long partial = pipelineRunRepo.countByStatusAndStartedAtGreaterThanEqual("PARTIAL", since);
        long awaiting = pipelineRunRepo.countByStatus("AWAITING_APPROVAL");

        summary.put("totalRuns", total);
        summary.put("completedRuns", completed);
        summary.put("failedRuns", failed);
        summary.put("partialRuns", partial);
        summary.put("openApprovalGates", awaiting);
        summary.put("completionPct", total == 0 ? 0 : Math.round((completed * 100.0) / total));

        // ── Active users (anyone who created a user account OR may have run) ─
        //    cheap proxy: count of distinct userIds via allActive filter on lastLoginAt
        long activeUsers = allActive.stream()
                .filter(u -> u.getLastLoginAt() != null && u.getLastLoginAt().isAfter(since))
                .count();
        summary.put("activeUsers", activeUsers);
        summary.put("totalUsers", allActive.size());

        // ── Header labels ────────────────────────────────────────────────────
        LocalDate startDate = since.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
        LocalDate endDate = now.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
        summary.put("periodStart", DATE_FMT.format(startDate));
        summary.put("periodEnd", DATE_FMT.format(endDate));
        summary.put("periodLabel", DATE_FMT.format(startDate) + " → " + DATE_FMT.format(endDate));

        return summary;
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}
