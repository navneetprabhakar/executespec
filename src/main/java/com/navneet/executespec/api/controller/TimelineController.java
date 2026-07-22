package com.navneet.executespec.api.controller;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.StateStore.ModelComparisonData;
import com.navneet.executespec.state.StateStore.TimelineData;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TimelineController {

  private final StateStore stateStore;
  private final RunAccessGuard guard;

  /**
   * Returns raw timeline data for a run: tasks, gate events, compaction events, messages. The Web
   * UI uses this to draw the Gantt chart and comms graph.
   */
  @GetMapping("/runs/{runId}/timeline")
  public ResponseEntity<TimelineData> timeline(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunAccess(runId, userDetails);
    try {
      return ResponseEntity.ok(stateStore.buildTimeline(runId));
    } catch (Exception e) {
      return ResponseEntity.notFound().build();
    }
  }

  /**
   * Returns per-model task statistics for the last {@code days} days. Scoped to the caller's org
   * (SUPER_ADMIN sees global data).
   */
  @GetMapping("/model-comparison")
  public ResponseEntity<ModelComparisonData> modelComparison(
      @RequestParam(defaultValue = "30") int days,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    // RR-1191 — GLOBAL requires explicit SUPER_ADMIN authority; a null-org ordinary user is
    // refused rather than silently widened to cross-tenant data. `days` is bounded to avoid
    // caller-controlled unbounded history scans.
    int windowDays = boundedDays(days);
    RunAccessGuard.AnalyticsScope scope = guard.resolveAnalyticsScope(userDetails);
    if (scope.global()) {
      return ResponseEntity.ok(stateStore.computeModelComparison(windowDays));
    }
    return ResponseEntity.ok(stateStore.computeModelComparisonForOrg(windowDays, scope.orgId()));
  }

  /** Bound caller-controlled history windows to a small positive maximum (RR-1191 / RR-243). */
  static int boundedDays(int days) {
    if (days < 1 || days > 366) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "days must be between 1 and 366.");
    }
    return days;
  }
}
