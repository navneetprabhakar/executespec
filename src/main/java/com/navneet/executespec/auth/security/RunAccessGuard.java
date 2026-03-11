package com.navneet.executespec.auth.security;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Centralised access-control guard for single-run endpoints.
 *
 * <p>Mirrors the row-level logic in {@link RunVisibilitySpec} but operates on
 * a single {@link PipelineRun} instead of a JPA {@code Specification}.
 *
 * <h3>Role hierarchy</h3>
 * <ul>
 *   <li><b>SUPER_ADMIN</b> — unrestricted access to every run</li>
 *   <li><b>ORG_ADMIN / BILLING_ADMIN</b> — runs that belong to the same org</li>
 *   <li><b>DEVELOPER</b> — all runs in their org (projects are shared grouping tools)</li>
 *   <li><b>FREE user</b> — own runs only</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RunAccessGuard {

    private final StateStore stateStore;

    // ── Run-level access ─────────────────────────────────────────────────────

    /**
     * Loads the run and asserts the caller may access it.
     *
     * @return the {@link PipelineRun} if access is granted
     * @throws ResponseStatusException 404 if the run does not exist,
     *                                  401 if unauthenticated,
     *                                  403 if the user has no visibility
     */
    public PipelineRun requireRunAccess(String runId, AppUserDetails userDetails) {
        PipelineRun run = stateStore.findRun(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Run not found: " + runId));
        assertAccess(run, userDetails);
        return run;
    }

    /**
     * Asserts the caller may access the given (already-loaded) run.
     */
    public void assertAccess(PipelineRun run, AppUserDetails userDetails) {
        requireAuthenticated(userDetails);

        AppUser user = userDetails.getUser();
        OrgMember orgMember = userDetails.getOrgMember();

        // SUPER_ADMIN — unrestricted
        if ("SUPER_ADMIN".equals(user.getGlobalRole())) return;

        // ORG_ADMIN / BILLING_ADMIN — same org
        if (orgMember != null
                && ("ORG_ADMIN".equals(orgMember.getOrgRole())
                    || "BILLING_ADMIN".equals(orgMember.getOrgRole()))) {
            if (user.getOrgId() != null && user.getOrgId().equals(run.getOrgId())) return;
            throw forbidden(run);
        }

        // DEVELOPER — all runs in their org (projects are shared grouping tools)
        if (orgMember != null && "DEVELOPER".equals(orgMember.getOrgRole())) {
            if (user.getOrgId() != null && user.getOrgId().equals(run.getOrgId())) return;
            throw forbidden(run);
        }

        // FREE user — own runs only
        if (isOwner(user, run)) return;

        throw forbidden(run);
    }

    // ── Role helpers ─────────────────────────────────────────────────────────

    /**
     * Throws 401 if no authentication principal is present.
     */
    public void requireAuthenticated(AppUserDetails userDetails) {
        if (userDetails == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Authentication required");
        }
    }

    /**
     * Asserts the caller is a SUPER_ADMIN.
     */
    public void requireSuperAdmin(AppUserDetails userDetails) {
        requireAuthenticated(userDetails);
        if (!"SUPER_ADMIN".equals(userDetails.getUser().getGlobalRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Super-admin access required");
        }
    }

    /**
     * Asserts the caller is at least an ORG_ADMIN (or SUPER_ADMIN).
     */
    public void requireOrgAdmin(AppUserDetails userDetails) {
        requireAuthenticated(userDetails);
        if ("SUPER_ADMIN".equals(userDetails.getUser().getGlobalRole())) return;
        if (userDetails.getOrgMember() != null
                && "ORG_ADMIN".equals(userDetails.getOrgMember().getOrgRole())) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
            "Org-admin access required");
    }

    /**
     * Returns the caller's org ID, or {@code null} for SUPER_ADMIN (global scope).
     */
    public String getOrgIdOrNull(AppUserDetails userDetails) {
        requireAuthenticated(userDetails);
        if ("SUPER_ADMIN".equals(userDetails.getUser().getGlobalRole())) return null;
        return userDetails.getUser().getOrgId();
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private boolean isOwner(AppUser user, PipelineRun run) {
        return user.getId() != null && user.getId().equals(run.getCreatedByUserId());
    }

    private ResponseStatusException forbidden(PipelineRun run) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN,
            "Access denied to run " + run.getRunId());
    }
}
