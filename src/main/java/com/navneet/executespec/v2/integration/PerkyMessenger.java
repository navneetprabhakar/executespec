package com.navneet.executespec.v2.integration;

import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Periodically emits encouraging {@code LOG} SSE events during long-running
 * pipeline phases so the live log feels alive even when the worker is mid-LLM-call
 * and no other events are flowing. Doubles as a lightweight heartbeat that keeps
 * the SSE stream warm on proxies with short idle timeouts.
 *
 * <p>Call {@link #start(String, String)} when a phase begins (PLANNING / WORKING /
 * REVIEWING), {@link #stop(String)} when it ends. Safe to start/stop multiple
 * times per run; each call replaces the previous scheduled ping so we never
 * double-emit.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PerkyMessenger {

    private static final long INITIAL_DELAY_SEC = 12;
    private static final long INTERVAL_SEC = 15;

    private static final String[] PLANNING = {
            "Whiteboarding the approach...",
            "Breaking the spec into bite-sized tasks...",
            "Picking the right tools for the job...",
    };
    private static final String[] WORKING = {
            "Drafting files...",
            "Stitching the pieces together...",
            "Hammering out the implementation...",
            "Checking the build...",
            "Typing fast, thinking faster...",
    };
    private static final String[] REVIEWING = {
            "Asking the reviewer to squint at the code...",
            "Running the wiring pass...",
            "Double-checking imports and integrations...",
            "Final build, final breath...",
    };

    private final SseBroadcaster broadcaster;
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "v2-perky-messenger");
                t.setDaemon(true);
                return t;
            });
    private final Map<String, ScheduledFuture<?>> active = new ConcurrentHashMap<>();
    private final Random random = new Random();

    /** v2.5.12 — graceful shutdown of the scheduler on SIGTERM. */
    @jakarta.annotation.PreDestroy
    public void shutdownScheduler() {
        active.values().forEach(f -> f.cancel(false));
        active.clear();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }

    public void start(String runId, String phase) {
        stop(runId);
        String[] bank = bankFor(phase);
        ScheduledFuture<?> f = scheduler.scheduleAtFixedRate(() -> {
            try {
                String msg = bank[random.nextInt(bank.length)];
                broadcaster.emit(runId, RunEvent.log(runId, null, msg));
            } catch (Exception e) {
                log.debug("PerkyMessenger emit failed for run={}: {}", runId, e.getMessage());
            }
        }, INITIAL_DELAY_SEC, INTERVAL_SEC, TimeUnit.SECONDS);
        active.put(runId, f);
    }

    public void stop(String runId) {
        ScheduledFuture<?> f = active.remove(runId);
        if (f != null) f.cancel(false);
    }

    private static String[] bankFor(String phase) {
        if (phase == null) return WORKING;
        return switch (phase.toUpperCase()) {
            case "PLANNING" -> PLANNING;
            case "REVIEWING" -> REVIEWING;
            default -> WORKING;
        };
    }
}
