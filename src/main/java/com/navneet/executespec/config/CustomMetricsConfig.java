package com.navneet.executespec.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registers custom Micrometer meters for ExecuteSpec business metrics.
 *
 * <p>These complement the standard auto-instrumented metrics (HTTP, JVM, HikariCP,
 * Redis, Spring AI) with pipeline-specific observability:
 * <ul>
 *   <li>Run lifecycle counters and timers</li>
 *   <li>LLM call metrics (tokens, fallbacks, errors)</li>
 *   <li>Task execution metrics</li>
 *   <li>Preview and deploy lifecycle metrics</li>
 *   <li>Agent handover timers</li>
 * </ul>
 *
 * <p>Meters are registered eagerly so they appear in /actuator/metrics immediately.
 * Services inject {@link MeterRegistry} directly and use the static builder methods
 * on {@link Counter}, {@link Timer}, etc. to record values at runtime.
 *
 * <p>This class provides the {@link Metrics} holder with pre-registered gauge
 * atomics and convenience access to meter names.
 */
@Configuration
@Slf4j
public class CustomMetricsConfig {

    // ── Gauge backing fields (updated by services via Metrics.xxx.set()) ────────

    /** Gauges are backed by AtomicInteger — services update these directly. */
    public static final class Metrics {
        public static final AtomicInteger ACTIVE_RUNS = new AtomicInteger(0);
        public static final AtomicInteger QUEUED_RUNS = new AtomicInteger(0);
        public static final AtomicInteger ACTIVE_PREVIEWS = new AtomicInteger(0);
        public static final AtomicInteger ACTIVE_DEPLOYS = new AtomicInteger(0);

        private Metrics() {}
    }

    // ── Meter name constants (used by services to record metrics) ───────────────

    /** Run lifecycle */
    public static final String RUN_TOTAL = "executespec.run.total";
    public static final String RUN_DURATION = "executespec.run.duration";

    /** Task lifecycle */
    public static final String TASK_TOTAL = "executespec.task.total";
    public static final String TASK_DURATION = "executespec.task.duration";

    /** LLM calls */
    public static final String LLM_CALLS = "executespec.llm.calls";
    public static final String LLM_TOKENS_INPUT = "executespec.llm.tokens.input";
    public static final String LLM_TOKENS_OUTPUT = "executespec.llm.tokens.output";
    public static final String LLM_ERRORS = "executespec.llm.errors";
    public static final String LLM_FALLBACK = "executespec.llm.fallback";
    public static final String LLM_DURATION = "executespec.llm.duration";
    public static final String LLM_RETRY_COUNT = "executespec.llm.retry_count";

    /** Tool calls */
    public static final String TOOL_CALLS = "executespec.tool.calls";

    /** Validation */
    public static final String VALIDATION_TOTAL = "executespec.validation.total";

    /** Gate wait times */
    public static final String GATE_WAIT_TIME = "executespec.gate.wait_time";

    /** Agent handover */
    public static final String HANDOVER_DURATION = "executespec.handover.duration";

    /** Monitor */
    public static final String STUCK_DETECTED = "executespec.monitor.stuck_detected";

    /** Preview */
    public static final String PREVIEW_STARTS = "executespec.preview.starts";
    public static final String PREVIEW_DURATION = "executespec.preview.duration";
    public static final String PREVIEW_BUILD_DURATION = "executespec.preview.build_duration";
    public static final String PREVIEW_DEPLOY_DURATION = "executespec.preview.deploy_duration";
    public static final String PREVIEW_FAILURES = "executespec.preview.failures";

    /** Per-provider run pool */
    public static final String PROVIDER_POOL_ACTIVE = "executespec.provider.pool.active";
    public static final String PROVIDER_POOL_QUEUED = "executespec.provider.pool.queued";
    public static final String PROVIDER_POOL_CAPACITY = "executespec.provider.pool.capacity";

    /** Deploy */
    public static final String DEPLOY_TOTAL = "executespec.deploy.total";
    public static final String DEPLOY_DURATION = "executespec.deploy.duration";
    public static final String DEPLOY_BUILD_DURATION = "executespec.deploy.build_duration";
    public static final String DEPLOY_HEALTH_CHECK_DURATION = "executespec.deploy.health_check_duration";
    public static final String DEPLOY_FAILURES = "executespec.deploy.failures";

    public CustomMetricsConfig(MeterRegistry registry) {
        // ── Register gauges (backed by AtomicInteger) ───────────────────────────
        Gauge.builder("executespec.run.active", Metrics.ACTIVE_RUNS, AtomicInteger::get)
            .description("Number of currently executing pipeline runs")
            .register(registry);

        Gauge.builder("executespec.run.queued", Metrics.QUEUED_RUNS, AtomicInteger::get)
            .description("Number of pipeline runs waiting for a slot")
            .register(registry);

        Gauge.builder("executespec.preview.active", Metrics.ACTIVE_PREVIEWS, AtomicInteger::get)
            .description("Number of live preview sessions")
            .register(registry);

        Gauge.builder("executespec.deploy.active", Metrics.ACTIVE_DEPLOYS, AtomicInteger::get)
            .description("Number of in-progress deployments")
            .register(registry);

        log.info("Custom ExecuteSpec metrics registered: runs, tasks, LLM, tools, preview, deploy");
    }
}
