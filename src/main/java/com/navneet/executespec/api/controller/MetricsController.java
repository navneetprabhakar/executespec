package com.navneet.executespec.api.controller;

import com.navneet.executespec.api.dto.MetricsResponse;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.StateStore.MetricsData;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
public class MetricsController {

  private final StateStore stateStore;

  /**
   * Returns metrics scoped to the caller's organisation. SUPER_ADMIN sees global metrics; ORG_ADMIN
   * sees org-filtered metrics. Users without an org see only their own org's runs (which is none if
   * no org).
   */
  @GetMapping
  public ResponseEntity<MetricsResponse> metrics(
      @RequestParam(defaultValue = "0") int days,
      @AuthenticationPrincipal AppUserDetails userDetails) {

    MetricsData d;
    boolean isSuperAdmin =
        userDetails != null && "SUPER_ADMIN".equals(userDetails.getUser().getGlobalRole());

    if (isSuperAdmin) {
      d = stateStore.computeMetrics(days);
    } else if (userDetails != null && userDetails.getUser().getOrgId() != null) {
      d = stateStore.computeMetricsForOrg(userDetails.getUser().getOrgId(), days);
    } else {
      d = new MetricsData(0, 0, 0, 0, 0, 0, 0);
    }

    return ResponseEntity.ok(
        new MetricsResponse(
            d.totalRuns(),
            d.completedRuns(),
            d.failedRuns(),
            d.activeRuns(),
            d.queuedRuns(),
            d.totalInputTokens(),
            d.totalOutputTokens()));
  }
}
