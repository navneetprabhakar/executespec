package com.navneet.executespec.api.controller;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.StateStore.UserRunsSummaryData;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserRunsController {

  private final StateStore stateStore;
  private final RunAccessGuard guard;

  /**
   * Returns per-user run statistics for the last {@code days} days. Scoped to the caller's org
   * (SUPER_ADMIN sees global data).
   */
  @GetMapping("/user-runs-summary")
  public ResponseEntity<UserRunsSummaryData> userRunsSummary(
      @RequestParam(defaultValue = "30") int days,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    // RR-1191 — GLOBAL requires explicit SUPER_ADMIN authority; a null-org ordinary user is
    // refused rather than silently widened to cross-tenant user display names/activity. `days`
    // is bounded to avoid caller-controlled unbounded history scans.
    if (days < 1 || days > 366) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "days must be between 1 and 366.");
    }
    RunAccessGuard.AnalyticsScope scope = guard.resolveAnalyticsScope(userDetails);
    if (scope.global()) {
      return ResponseEntity.ok(stateStore.computeUserRunsSummaryGlobal(days));
    }
    return ResponseEntity.ok(stateStore.computeUserRunsSummary(scope.orgId(), days));
  }
}
