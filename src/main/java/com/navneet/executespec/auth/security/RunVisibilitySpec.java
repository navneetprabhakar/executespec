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

      // Safety fallback: user has orgId but orgMember record is missing/orphaned.
      // Still grant org-scoped visibility so they can see runs in their org.
      if (user.getOrgId() != null) {
        return cb.equal(root.get("orgId"), user.getOrgId());
      }

      // FREE user (no org) — see own runs only
      return cb.equal(root.get("createdByUserId"), user.getId());
    };
  }
}
