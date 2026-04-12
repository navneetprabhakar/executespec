package com.navneet.executespec.v2.integration;

import com.navneet.executespec.state.entity.PipelineRun;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Rejects V1-controller actions that V2 doesn't support yet with a clean
 * HTTP 501 (Not Implemented). Keeps the UI unbroken — buttons still render,
 * click emits a toast via the standard error path rather than a 500.
 *
 * <p>Usage:
 * <pre>
 *   PipelineRun run = guard.requireRunAccess(runId, userDetails);
 *   v2Guard.ensureNotV2(run, "approval-gate");
 *   ... existing V1 handler body ...
 * </pre>
 *
 * <p>Features deferred per the Phase 11 UI-parity plan:
 * approval gate, retry, pause/resume, preview, deploy, chat/apply/iterate,
 * test execution, reports, edit-plan, rework, reopen-merge, clarify.
 * The {@code feature} string is surfaced in the response body so the frontend
 * can show a specific "not yet available on V2" toast.
 */
@Component
public class V2DeferredActionGuard {

    public void ensureNotV2(PipelineRun run, String feature) {
        if (run == null) return;
        if ("v2".equals(run.getHarnessVersion())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_IMPLEMENTED,
                    "Feature '" + feature + "' is not yet available on V2 runs. "
                            + "Coming in a subsequent release.");
        }
    }
}
