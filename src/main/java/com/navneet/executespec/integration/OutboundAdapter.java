package com.navneet.executespec.integration;

import com.navneet.executespec.agent.model.ReviewReport;
import com.navneet.executespec.state.entity.IntegrationRun;
import com.navneet.executespec.state.entity.PipelineRun;

/**
 * Adapter for writing run results back to an external integration source.
 * Each integration implements this to post comments, notes, or pages on run status changes.
 */
public interface OutboundAdapter {

    String sourceType();

    /** Called when a run transitions to PLANNING. */
    void onRunStarted(IntegrationRun integrationRun, PipelineRun run);

    /** Called when run completes successfully (COMPLETE). */
    void onRunComplete(IntegrationRun integrationRun, PipelineRun run, ReviewReport review);

    /** Called when run fails (FAILED). */
    void onRunFailed(IntegrationRun integrationRun, PipelineRun run, String reason);
}
