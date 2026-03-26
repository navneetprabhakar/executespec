package com.navneet.executespec.harness;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * On application startup, logs that auto-resume is disabled.
 *
 * <p>Auto-resume was removed — users can manually resume stuck runs via the UI.
 * This bean is kept so the startup log message is emitted.</p>
 */
@Component
@Slf4j
public class StartupRunResumer {

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        // Auto-resume disabled — users can manually resume stuck runs via the UI.
        // Runs stuck in WORKING/STUCK/REVIEWING after a server restart will show
        // their current status; users click "Resume" when ready.
        log.info("Startup auto-resume is disabled — stuck runs can be resumed manually via the UI");
    }
}
