package com.navneet.executespec.governance;

import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * M-032 + M-033 + M-039 (Wave 3) — three governance guards in one
 * service so the call sites have a single import.
 *
 * <ul>
 *   <li>{@link #assertCanApproveCost} — M-032: when org has set a
 *       require_admin_approval_above_usd threshold, only ORG_ADMIN
 *       / BILLING_ADMIN / SUPER_ADMIN can approve a plan whose
 *       buffered estimate exceeds it.</li>
 *   <li>{@link #assertProjectSpendCap} — M-033: rejects approval
 *       when (project's MTD spend + new run's estimate) exceeds
 *       project.spend_cap_usd_monthly.</li>
 *   <li>{@link #assertRepoOwnerAllowed} — M-039: rejects repo
 *       link/create when the GH owner isn't in
 *       org.allowed_github_owners.</li>
 * </ul>
 *
 * <p>All three guards are no-ops when the relevant cap column is
 * NULL, so existing orgs/projects keep working unchanged. The
 * guards are SUPER_ADMIN-bypass-aware (incident response).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GovernanceGuard {

    private final OrgRepository orgRepo;
    private final ProjectRepository projectRepo;
    private final PipelineRunRepository runRepo;

    private boolean isSuperAdmin(AppUserDetails u) {
        return u != null && u.getUser() != null
                && "SUPER_ADMIN".equals(u.getUser().getGlobalRole());
    }

    private boolean isOrgAdminOrBilling(AppUserDetails u) {
        if (u == null || u.getOrgMember() == null) return false;
        String role = u.getOrgMember().getOrgRole();
        return "ORG_ADMIN".equals(role) || "BILLING_ADMIN".equals(role);
    }

    /**
     * M-032 — gate plan approval on the per-org cost-threshold policy.
     * Throws 403 (not 402) since this is RBAC, not "out of budget":
     * the user CAN approve eventually, just not without escalation.
     *
     * @param orgId          the org owning the run; null = personal
     *                        run, no policy applies.
     * @param estimatedUsd   the buffered cost estimate for this run.
     * @param caller         who's clicking Approve.
     */
    public void assertCanApproveCost(String orgId, double estimatedUsd, AppUserDetails caller) {
        if (orgId == null || isSuperAdmin(caller)) return;
        if (isOrgAdminOrBilling(caller)) return;
        Org org = orgRepo.findById(orgId).orElse(null);
        if (org == null || org.getRequireAdminApprovalAboveUsd() == null) return;
        BigDecimal threshold = org.getRequireAdminApprovalAboveUsd();
        if (BigDecimal.valueOf(estimatedUsd).compareTo(threshold) > 0) {
            log.warn("[Governance/M-032] approval blocked — org={} threshold=${} estimate=${} caller={}",
                    orgId, threshold, estimatedUsd,
                    caller == null ? "?" : caller.getUser().getId());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Plans estimated above $" + threshold + " in this org require approval by an "
                    + "ORG_ADMIN or BILLING_ADMIN. Current estimate: $"
                    + String.format("%.2f", estimatedUsd) + ".");
        }
    }

    /**
     * M-033 — gate plan approval on the per-project monthly spend cap.
     * Sums actualCostUsd of every run on this project where
     * startedAt is in the current calendar month (UTC; we don't
     * complicate this with the Asia/Kolkata billing zone — the cap
     * is a guard rail, not a billing line item).
     *
     * @param projectId      project owning the run; null = ungrouped, no cap.
     * @param estimatedUsd   the buffered cost estimate for this run.
     */
    public void assertProjectSpendCap(String projectId, double estimatedUsd) {
        if (projectId == null) return;
        Project project = projectRepo.findById(projectId).orElse(null);
        if (project == null || project.getSpendCapUsdMonthly() == null) return;
        BigDecimal cap = project.getSpendCapUsdMonthly();

        Instant monthStart = ZonedDateTime.now(ZoneOffset.UTC)
                .withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0)
                .toInstant();
        double mtdSpend = projectMtdSpend(projectId, monthStart);
        double projected = mtdSpend + estimatedUsd;

        if (BigDecimal.valueOf(projected).compareTo(cap) > 0) {
            log.warn("[Governance/M-033] approval blocked — project={} cap=${} mtd=${} estimate=${} projected=${}",
                    projectId, cap, mtdSpend, estimatedUsd, projected);
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                "This project has a $" + cap + "/month spend cap. "
                    + "Approving this run would push the month-to-date total to $"
                    + String.format("%.2f", projected)
                    + ". Ask the project owner to raise the cap or split the work.");
        }
    }

    private double projectMtdSpend(String projectId, Instant monthStart) {
        // Cheaper than loading every run — sum the cost column directly.
        Double sum = runRepo.sumActualCostByProjectAndSince(projectId, monthStart);
        return sum == null ? 0.0 : sum;
    }

    /**
     * M-039 — reject GitHub-owner outside the org's allowlist.
     * Empty/NULL allowedGithubOwners = allow-all. Comma-separated;
     * matched case-insensitively. SUPER_ADMIN bypass.
     */
    public void assertRepoOwnerAllowed(String orgId, String githubOwner, AppUserDetails caller) {
        if (orgId == null || githubOwner == null || githubOwner.isBlank()) return;
        if (isSuperAdmin(caller)) return;
        Org org = orgRepo.findById(orgId).orElse(null);
        if (org == null || org.getAllowedGithubOwners() == null
                || org.getAllowedGithubOwners().isBlank()) return;

        Set<String> allowed = new HashSet<>();
        for (String s : org.getAllowedGithubOwners().split(",")) {
            String t = s.trim().toLowerCase();
            if (!t.isEmpty()) allowed.add(t);
        }
        if (!allowed.contains(githubOwner.trim().toLowerCase())) {
            log.warn("[Governance/M-039] repo link blocked — org={} attempted owner={} allowed={}",
                    orgId, githubOwner, allowed);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "GitHub owner '" + githubOwner + "' is not on this org's allowlist. "
                    + "Permitted: " + String.join(", ", allowed)
                    + ". Ask an ORG_ADMIN to update the allowlist.");
        }
    }

    /** For tests + callers that already know their allowlist string. */
    public static boolean ownerAllowedBy(String allowedCsv, String owner) {
        if (allowedCsv == null || allowedCsv.isBlank() || owner == null || owner.isBlank()) return true;
        return Arrays.stream(allowedCsv.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .anyMatch(s -> s.equals(owner.trim().toLowerCase()));
    }
}
