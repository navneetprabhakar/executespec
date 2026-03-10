package com.navneet.executespec.api.controller;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.StateStore.UserRunsSummaryData;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserRunsController {

    private final StateStore stateStore;
    private final RunAccessGuard guard;

    /**
     * Returns per-user run statistics for the last {@code days} days.
     * Scoped to the caller's org (SUPER_ADMIN sees global data).
     */
    @GetMapping("/user-runs-summary")
    public ResponseEntity<UserRunsSummaryData> userRunsSummary(
            @RequestParam(defaultValue = "30") int days,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireAuthenticated(userDetails);
        String orgId = guard.getOrgIdOrNull(userDetails);
        if (orgId != null) {
            return ResponseEntity.ok(stateStore.computeUserRunsSummary(orgId, days));
        }
        // SUPER_ADMIN: global view
        return ResponseEntity.ok(stateStore.computeUserRunsSummaryGlobal(days));
    }
}
