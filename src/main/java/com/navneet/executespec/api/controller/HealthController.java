package com.navneet.executespec.api.controller;

import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.StateStore.MetricsData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnectionFactory;
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
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>GET /api/health — Liveness probe. Always 200 if JVM alive. Zero DB/Redis calls.
 *       DB + Redis status are cached (refreshed every 30s in background).
 *       MIG auto-healer MUST use this endpoint.</li>
 *   <li>GET /api/health/ready — Readiness probe. Checks DB live. Returns 503 if DB down.
 *       LB backend service should use this to stop routing traffic when DB is unreachable.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
@Slf4j
public class HealthController {

    private static final Instant START_TIME = Instant.now();

    /** Cache interval for DB/Redis/metrics checks — prevents pool exhaustion from frequent probes. */
    private static final long CACHE_INTERVAL_MS = 30_000;

    private final DataSource dataSource;
    private final StateStore stateStore;
    private final RedisConnectionFactory redisConnectionFactory;

    // ── Cached state (refreshed every 30s) ───────────────────────────────────
    private final AtomicBoolean cachedDbUp = new AtomicBoolean(false);
    private final AtomicBoolean cachedRedisUp = new AtomicBoolean(false);
    private final AtomicReference<MetricsData> cachedMetrics =
        new AtomicReference<>(new MetricsData(0L, 0L, 0L, 0L, 0L, 0L, 0L));
    private final AtomicReference<Instant> lastCacheRefresh = new AtomicReference<>(Instant.EPOCH);

    /**
     * Liveness probe — always 200 if the JVM is running.
     * No inline DB or Redis calls — all data is cached.
     * Used by MIG health check to prevent death-spiral on transient failures.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        refreshCacheIfStale();

        long uptimeMs = Duration.between(START_TIME, Instant.now()).toMillis();
        boolean dbUp = cachedDbUp.get();
        boolean redisUp = cachedRedisUp.get();
        MetricsData m = cachedMetrics.get();

        String status = (dbUp && redisUp) ? "UP" : "DEGRADED";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",       status);
        body.put("uptimeMs",     uptimeMs);
        body.put("uptime",       formatUptime(uptimeMs));
        body.put("activeRuns",   m.activeRuns());
        body.put("queuedRuns",   m.queuedRuns());
        body.put("totalRuns",    m.totalRuns());
        body.put("completedRuns",m.completedRuns());
        body.put("failedRuns",   m.failedRuns());
        body.put("db",           dbUp ? "UP" : "DOWN");
        body.put("redis",        redisUp ? "UP" : "DOWN");
        body.put("timestamp",    Instant.now().toString());

        return ResponseEntity.ok(body);
    }

    /**
     * Readiness probe — 200 only if DB is reachable.
     * LB backend service should use this for traffic routing decisions.
     * Does an inline DB check (not cached) for accuracy.
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

    // ── Cached refresh ───────────────────────────────────────────────────────

    private void refreshCacheIfStale() {
        Instant last = lastCacheRefresh.get();
        if (Duration.between(last, Instant.now()).toMillis() > CACHE_INTERVAL_MS) {
            if (lastCacheRefresh.compareAndSet(last, Instant.now())) {
                try { cachedDbUp.set(checkDatabase()); } catch (Exception e) { cachedDbUp.set(false); }
                try { cachedRedisUp.set(checkRedis()); } catch (Exception e) { cachedRedisUp.set(false); }
                try { cachedMetrics.set(stateStore.computeMetrics()); } catch (Exception e) { /* keep old */ }
            }
        }
    }

    // ── Live checks (used by refresh + readiness) ────────────────────────────

    private boolean checkDatabase() {
        try (var conn = dataSource.getConnection()) {
            return conn.isValid(2);
        } catch (Exception e) {
            log.warn("Health DB check failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean checkRedis() {
        try (var conn = redisConnectionFactory.getConnection()) {
            return "PONG".equals(conn.ping());
        } catch (Exception e) {
            log.warn("Health Redis check failed: {}", e.getMessage());
            return false;
        }
    }

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
