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
   * to see.
   */
  public Specification<PipelineRun> forUser(AppUser user, OrgMember orgMember) {
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
      return cb.equal(root.get("createdByUserId"), user.getId());
    };
  }
}
