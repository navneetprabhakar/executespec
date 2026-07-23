package com.navneet.executespec.auth.security;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/** Builds JPA Specifications for run visibility based on the current user's role. */
@Component
public class RunVisibilitySpec {

  /**
   * Returns a Specification that filters pipeline_run rows to only those the given user is allowed
   * to see. Backward-compatible overload with no external project memberships.
   */
  public Specification<PipelineRun> forUser(AppUser user, OrgMember orgMember) {
    return forUser(user, orgMember, java.util.List.of());
  }

  /**
   * Returns a Specification that filters pipeline_run rows to only those the given user is allowed
   * to see.
   *
   * <p>RR-212 — {@code accessibleProjectIds} are the ids of projects the user is an ACTIVE member
   * of (including cross-org {@code ProjectMember} collaborations). Runs of those projects are OR-ed
   * into the own-runs fallback so a collaborator can DISCOVER the host project's runs in a normal
   * list, matching what the per-run detail guard already permits. It deliberately does NOT broaden
   * to the host org — only that project's runs become visible.
   */
  public Specification<PipelineRun> forUser(
      AppUser user, OrgMember orgMember, java.util.Collection<String> accessibleProjectIds) {
    return (Root<PipelineRun> root, CriteriaQuery<?> query, CriteriaBuilder cb) -> {

      // SUPER_ADMIN sees everything
      if ("SUPER_ADMIN".equals(user.getGlobalRole())) {
        return cb.conjunction();
      }

      // ORG_ADMIN or BILLING_ADMIN — see all runs in their org
      if (orgMember != null
          && ("ORG_ADMIN".equals(orgMember.getOrgRole())
              || "BILLING_ADMIN".equals(orgMember.getOrgRole()))) {
        return cb.equal(root.get("orgId"), user.getOrgId());
      }

      // DEVELOPER — see all runs in their org (projects are shared grouping tools)
      if (orgMember != null && "DEVELOPER".equals(orgMember.getOrgRole())) {
        return cb.equal(root.get("orgId"), user.getOrgId());
      }

      // RR-210: NO org-wide fallback. `app_user.org_id` is a denormalized pointer; `org_member` is
      // the authoritative membership record. Granting tenant-wide run visibility from the pointer
      // alone meant a removed/expired membership kept working for as long as the stale pointer
      // survived, and it overrode the very table that revocation writes to. Absent or unrecognized
      // membership now falls through to own-runs-only.
      var ownRuns = cb.equal(root.get("createdByUserId"), user.getId());

      // RR-212 — plus runs of projects the user is an active member of (cross-org collaborations
      // included), so a collaborator can find the host project's runs, not only open a known URL.
      if (accessibleProjectIds != null && !accessibleProjectIds.isEmpty()) {
        return cb.or(ownRuns, root.get("projectId").in(accessibleProjectIds));
      }
      return ownRuns;
    };
  }
}
