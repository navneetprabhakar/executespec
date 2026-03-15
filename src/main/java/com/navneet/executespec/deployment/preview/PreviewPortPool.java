package com.navneet.executespec.deployment.preview;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Legacy port pool — no longer used after Cloud Run migration.
 * Kept as a no-op to avoid breaking Spring dependency injection
 * for any remaining references. Will be removed in cleanup phase.
 */
@Component
@Slf4j
public class PreviewPortPool {

    public synchronized int allocate(String runId) {
        throw new PreviewException("PreviewPortPool is deprecated — previews now use Cloud Run");
    }

    public void release(int port) {
        // no-op
    }

    public void releaseByRunId(String runId) {
        // no-op
    }

    public int activeCount() {
        return 0;
    }
}
