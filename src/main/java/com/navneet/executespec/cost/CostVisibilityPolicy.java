package com.navneet.executespec.cost;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.security.AppUserDetails;

/**
 * RR-563 — raw provider-dollar (COGS) cost is a billing figure, not a general project-read field.
 *
 * <p>ExecuteSpec deliberately hides raw USD spend from customer-facing surfaces and exposes managed
 * AI credits instead (see root {@code CLAUDE.md}: "Customer-facing web surfaces show AI credits and
 * hide raw USD spend/caps unless the viewer is SUPER_ADMIN"). Several ordinary project-read
 * surfaces were still emitting raw {@code costUsd} / {@code actualCostUsd} to any project reader —
 * including non-billing developers and cross-org project collaborators.
 *
 * <p>This mirrors the RR-1552 predicate for per-attempt cost detail ({@link
 * RunCostEvidenceService#canViewPhysicalAttemptDetail}) but adds {@code BILLING_ADMIN}: the
 * dedicated billing role legitimately reads dollar figures, whereas per-attempt route detail (which
 * physical provider served each call) stays ORG_ADMIN/SUPER_ADMIN only. The check is scoped to the
 * resource's OWNING org, so a caller who is an admin of a DIFFERENT org (a cross-org collaborator)
 * does not inherit cost visibility on the host org's data.
 */
public final class CostVisibilityPolicy {

  private CostVisibilityPolicy() {}

  /**
   * Whether {@code userDetails} may see raw provider-dollar cost for a resource owned by {@code
   * resourceOrgId}.
   *
   * @param resourceOrgId the owning org of the run / scan / review / project (may be {@code null}
   *     or blank when the resource has no org scope, e.g. a personal run); with no org scope only
   *     global billing roles qualify.
   */
  public static boolean canViewRawCost(AppUserDetails userDetails, String resourceOrgId) {
    if (userDetails == null) return false;
    AppUser user = userDetails.getUser();
    if (user == null) return false;

    String globalRole = user.getGlobalRole();
    if ("SUPER_ADMIN".equals(globalRole) || "FINANCE".equals(globalRole)) {
      return true;
    }

    if (resourceOrgId == null || resourceOrgId.isBlank()) {
      // No owning-org scope to evaluate a billing role against — only the global roles above see
      // it.
      return false;
    }

    OrgMember member = userDetails.getOrgMember();
    if (member == null) return false;
    String orgRole = member.getOrgRole();
    boolean billingRole = "ORG_ADMIN".equals(orgRole) || "BILLING_ADMIN".equals(orgRole);
    // The member must be a billing admin of the SAME org that owns the resource. Matching both the
    // user's home org and the membership's org closes the cross-org-collaborator gap: an org admin
    // of their own workspace cannot read another tenant's project spend.
    return billingRole
        && resourceOrgId.equals(user.getOrgId())
        && resourceOrgId.equals(member.getOrgId());
  }
}
