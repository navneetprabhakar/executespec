package com.navneet.executespec.api.controller;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.StateStore.ModelComparisonData;
import com.navneet.executespec.state.StateStore.TimelineData;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TimelineController {

    private final StateStore stateStore;
    private final RunAccessGuard guard;

    /**
     * Returns raw timeline data for a run: tasks, gate events, compaction events, messages.
     * The Web UI uses this to draw the Gantt chart and comms graph.
     */
    @GetMapping("/runs/{runId}/timeline")
    public ResponseEntity<TimelineData> timeline(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(runId, userDetails);
        try {
            return ResponseEntity.ok(stateStore.buildTimeline(runId));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Returns per-model task statistics for the last {@code days} days.
     * Scoped to the caller's org (SUPER_ADMIN sees global data).
     */
    @GetMapping("/model-comparison")
    public ResponseEntity<ModelComparisonData> modelComparison(
            @RequestParam(defaultValue = "30") int days,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireAuthenticated(userDetails);
        String orgId = guard.getOrgIdOrNull(userDetails);
        if (orgId != null) {
            return ResponseEntity.ok(stateStore.computeModelComparisonForOrg(days, orgId));
        }
        return ResponseEntity.ok(stateStore.computeModelComparison(days));
    }
}
