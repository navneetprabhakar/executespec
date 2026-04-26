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
import java.util.regex.Pattern;

/**
 * E4.6 — drops old {@code activity_feed_y{N}m{NN}} partitions per the
 * platform-wide retention floor.
 *
 * <p>Per-tier retention is encoded by {@code activity.feed.retention.days}
 * (default 365 = ENTERPRISE-friendly). Tier-specific retention (FREE 30d
 * / PRO 90d / TEAM 180d / MAX 90d / ENTERPRISE unlimited) is enforced
 * upstream at query-time; the partition drop here is a coarse,
 * platform-wide GC.
 *
 * <p>Daily at 03:00 with a wide lock window. Detaches old partitions
 * via {@code DETACH PARTITION} then drops them — safer than direct DROP
 * (lets DBAs intervene if a detach is queued during the window).
 *
 * <p>{@code activity.feed.retention.dry-run=true} (default) makes the
 * job log what it would drop without dropping. Set to false in
 * application-prod.yml only after validating the partition list.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ActivityFeedRetentionJob {

    private static final Pattern PARTITION_NAME =
            Pattern.compile("^activity_feed_y(\\d{4})m(\\d{2})$");
    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyy'm'MM");

    private final JdbcTemplate jdbc;

    @Value("${activity.feed.retention.days:365}")
    private int retentionDays;

    @Value("${activity.feed.retention.dry-run:true}")
    private boolean dryRun;

    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "ActivityFeedRetentionJob", lockAtMostFor = "30m", lockAtLeastFor = "1m")
    public void enforceRetention() {
        run(LocalDate.now());
    }

    /** Test seam — pure, deterministic input. */
    int run(LocalDate today) {
        int dropped = 0;
        YearMonth cutoff = YearMonth.from(today.minusDays(retentionDays));
        java.util.List<String> partitions;
        try {
            partitions = jdbc.queryForList(
                    "SELECT inhrelid::regclass::text "
                            + "FROM pg_inherits WHERE inhparent = 'activity_feed'::regclass",
                    String.class);
        } catch (Exception ex) {
            log.warn("[ActivityFeedRetentionJob] could not list partitions: {}", ex.getMessage());
            return 0;
        }
        for (String name : partitions) {
            YearMonth ym = parsePartitionMonth(name);
            if (ym == null) continue;
            if (ym.isBefore(cutoff)) {
                if (dryRun) {
                    log.info("[ActivityFeedRetentionJob] DRY RUN: would drop {} (older than {})",
                            name, cutoff);
                    dropped++;
                } else {
                    if (dropPartition(name)) dropped++;
                }
            }
        }
        log.info("[ActivityFeedRetentionJob] {} {} partition(s) older than {} (retention {}d)",
                dryRun ? "would drop" : "dropped", dropped, cutoff, retentionDays);
        return dropped;
    }

    static YearMonth parsePartitionMonth(String tableName) {
        if (tableName == null) return null;
        // strip schema prefix if present (e.g. "public.activity_feed_y2026m04")
        String basename = tableName.contains(".")
                ? tableName.substring(tableName.lastIndexOf('.') + 1) : tableName;
        var m = PARTITION_NAME.matcher(basename);
        if (!m.matches()) return null;
        try {
            return YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        } catch (Exception ex) {
            return null;
        }
    }

    boolean dropPartition(String tableName) {
        try {
            jdbc.execute("ALTER TABLE activity_feed DETACH PARTITION " + tableName);
            jdbc.execute("DROP TABLE IF EXISTS " + tableName);
            log.info("[ActivityFeedRetentionJob] dropped partition {}", tableName);
            return true;
        } catch (Exception ex) {
            log.warn("[ActivityFeedRetentionJob] drop failed for {}: {}", tableName, ex.getMessage());
            return false;
        }
    }
}
