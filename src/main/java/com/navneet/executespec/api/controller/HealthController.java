package com.navneet.executespec.api.controller;

import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.StateStore.MetricsData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight health endpoint consumed by MIG/LB health checks
 * and by the dashboard header (for real-time server status badge).
 *
 * <p>GET /api/health → always 200 (JVM is alive). DB status is cached
 * and checked asynchronously to avoid connection pool exhaustion from
 * frequent health-check calls.
 *
 * <p>GET /api/health/ready → 200 if DB reachable, 503 otherwise.
 *
 * <p>The MIG auto-healer and load-balancer health checks use /api/health (liveness)
 * which never returns 503, preventing a death-spiral where a transient DB issue
 * causes the MIG to continuously recreate VMs.
 */
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
@Slf4j
public class HealthController {

    /** Wall-clock instant when the JVM started. Used to compute uptime. */
    private static final Instant START_TIME = Instant.now();

    /** Cache DB status for 30 seconds to avoid pool exhaustion from frequent health checks. */
    private static final long DB_CHECK_INTERVAL_MS = 30_000;

    private final DataSource    dataSource;
    private final StateStore    stateStore;

    // Cached DB status — prevents connection pool exhaustion when MIG/LB
    // health checks call /api/health every 15-30 seconds
    private final AtomicBoolean cachedDbUp = new AtomicBoolean(false);
    private final AtomicReference<Instant> lastDbCheck = new AtomicReference<>(Instant.EPOCH);

    /**
     * Liveness probe — always 200 if the JVM is running.
     * Used by MIG health check and load balancer.
     * DB status is cached and refreshed every 30s to avoid pool exhaustion.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        boolean dbUp = getCachedDbStatus();

        // Uptime
        long uptimeMs = Duration.between(START_TIME, Instant.now()).toMillis();
        String uptimeFmt = formatUptime(uptimeMs);

        // Run metrics — safe: never throws, caught internally in StateStore
        MetricsData m = safeMetrics();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",       dbUp ? "UP" : "DEGRADED");
        body.put("uptimeMs",     uptimeMs);
        body.put("uptime",       uptimeFmt);
        body.put("activeRuns",   m.activeRuns());
        body.put("queuedRuns",   m.queuedRuns());
        body.put("totalRuns",    m.totalRuns());
        body.put("completedRuns",m.completedRuns());
        body.put("failedRuns",   m.failedRuns());
        body.put("db",           dbUp ? "UP" : "DOWN");
        body.put("timestamp",    Instant.now().toString());

        // Always 200 — JVM is alive. MIG/LB must not kill the VM
        // just because the DB is transiently unreachable.
        return ResponseEntity.ok(body);
    }

    /**
     * Readiness probe — 200 only if the DB is reachable.
     * Use this for traffic routing decisions, NOT for MIG auto-healing.
     */
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        boolean dbUp = checkDatabase();
        Map<String, Object> body = Map.of(
            "status", dbUp ? "READY" : "NOT_READY",
            "db", dbUp ? "UP" : "DOWN",
            "timestamp", Instant.now().toString()
        );
        return dbUp
            ? ResponseEntity.ok(body)
            : ResponseEntity.status(503).body(body);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns cached DB status. Only performs an actual DB check every
     * {@link #DB_CHECK_INTERVAL_MS} ms to prevent connection pool exhaustion
     * from frequent MIG/LB health check calls.
     */
    private boolean getCachedDbStatus() {
        Instant last = lastDbCheck.get();
        if (Duration.between(last, Instant.now()).toMillis() > DB_CHECK_INTERVAL_MS) {
            // Time to refresh — but don't block other callers
            if (lastDbCheck.compareAndSet(last, Instant.now())) {
                try {
                    cachedDbUp.set(checkDatabase());
                } catch (Exception e) {
                    cachedDbUp.set(false);
                }
            }
        }
        return cachedDbUp.get();
    }

    private boolean checkDatabase() {
        try (var conn = dataSource.getConnection()) {
            return conn.isValid(2);           // 2-second validation timeout
        } catch (Exception e) {
            log.warn("Health DB check failed: {}", e.getMessage());
            return false;
        }
    }

    private MetricsData safeMetrics() {
        try {
            return stateStore.computeMetrics();
        } catch (Exception e) {
            log.debug("Health metrics fetch failed: {}", e.getMessage());
            return new MetricsData(0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }
    }

    /** Formats milliseconds as "Xh Ym Zs" — human-readable for the dashboard. */
    private String formatUptime(long ms) {
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }
}
