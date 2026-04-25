package com.navneet.executespec.activity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

/**
 * E1.10 — keeps the {@code activity_feed} partition window topped up.
 *
 * <p>V133 ships 12 monthly partitions (2026-04 through 2027-03). This job
 * runs daily at 02:30 and ensures partitions exist for the next
 * {@code activity.feed.partition.lookahead-months} months (default: 3),
 * so writes never hit the parent table without a child partition.
 *
 * <p>{@code @SchedulerLock} keeps the work single-execution across
 * multi-node deployments (Phase A NFS workspace makes this relevant).
 *
 * <p>Retention enforcement (drop partitions older than the per-tier
 * window) ships in E4.6, not here — that needs a per-project lookup
 * which is heavier than this lightweight create-only job.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ActivityFeedPartitionJob {

    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyy'm'MM");

    private final JdbcTemplate jdbc;

    @Value("${activity.feed.partition.lookahead-months:3}")
    private int lookaheadMonths;

    /**
     * Daily at 02:30 (off-peak). 30-min lock window is generous; the job
     * itself takes &lt;1s for the typical N=3 partition lookahead.
     */
    @Scheduled(cron = "0 30 2 * * *")
    @SchedulerLock(name = "ActivityFeedPartitionJob", lockAtMostFor = "30m", lockAtLeastFor = "1m")
    public void ensurePartitions() {
        ensurePartitionsFor(LocalDate.now(), lookaheadMonths);
    }

    /**
     * Package-private overload exposed for tests — deterministic input.
     * Returns the number of {@code CREATE TABLE} statements actually issued
     * (existing partitions are skipped via {@code IF NOT EXISTS}, but the
     * statement still executes; the count tracks all attempts).
     */
    int ensurePartitionsFor(LocalDate today, int monthsAhead) {
        if (monthsAhead < 1) return 0;
        YearMonth start = YearMonth.from(today);
        int created = 0;
        for (int i = 0; i <= monthsAhead; i++) {
            YearMonth target = start.plusMonths(i);
            String suffix = target.format(SUFFIX);
            String tableName = "activity_feed_y" + suffix;
            String fromDate = target.atDay(1).toString();
            String toDate = target.plusMonths(1).atDay(1).toString();
            String ddl = "CREATE TABLE IF NOT EXISTS " + tableName
                    + " PARTITION OF activity_feed FOR VALUES FROM ('"
                    + fromDate + "') TO ('" + toDate + "')";
            try {
                jdbc.execute(ddl);
                created++;
            } catch (Exception ex) {
                log.warn("[ActivityFeedPartitionJob] could not ensure partition {}: {}",
                        tableName, ex.getMessage());
            }
        }
        log.info("[ActivityFeedPartitionJob] ensured {} partitions starting {} (lookahead {}m)",
                created, start, monthsAhead);
        return created;
    }
}
