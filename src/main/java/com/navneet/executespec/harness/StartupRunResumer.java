package com.navneet.executespec.harness;

import com.navneet.executespec.recovery.RecoveryProperties;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * On application startup, resumes non-terminal runs that were interrupted by a server restart.
 * Controlled by {@code agent.recovery.auto-resume-on-startup} (default: false).
 * Waits {@code startup-resume-delay-seconds} after ApplicationReadyEvent before querying.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StartupRunResumer {

    private static final Set<String> TERMINAL_STATUSES = Set.of(
            "COMPLETE", "FAILED", "CANCELLED", "REJECTED", "PARTIAL"
    );

    private final RecoveryProperties recoveryProperties;
    private final PipelineRunRepository runRepository;
    private final PipelineHarness harness;

    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void onApplicationReady() {
        if (!recoveryProperties.isAutoResumeOnStartup()) {
            log.info("Startup auto-resume is disabled — stuck runs can be resumed manually via the UI");
            return;
        }

        int delaySec = recoveryProperties.getStartupResumeDelaySeconds();
        log.info("Startup auto-resume enabled — waiting {}s before scanning for resumable runs", delaySec);

        try {
            Thread.sleep(delaySec * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Startup resume interrupted during delay");
            return;
        }

        List<PipelineRun> nonTerminal = runRepository.findAll().stream()
                .filter(r -> r.getStatus() != null && !TERMINAL_STATUSES.contains(r.getStatus()))
                .toList();

        if (nonTerminal.isEmpty()) {
            log.info("No non-terminal runs found to resume after startup");
            return;
        }

        log.info("Found {} non-terminal run(s) to resume: {}", nonTerminal.size(),
                nonTerminal.stream().map(r -> r.getRunId() + "(" + r.getStatus() + ")").toList());

        for (PipelineRun run : nonTerminal) {
            try {
                log.info("Resuming run {} from status {}", run.getRunId(), run.getStatus());
                harness.resumeOnStartup(run);
            } catch (Exception e) {
                log.error("Failed to resume run {} on startup: {}", run.getRunId(), e.getMessage(), e);
            }
        }
    }
}
