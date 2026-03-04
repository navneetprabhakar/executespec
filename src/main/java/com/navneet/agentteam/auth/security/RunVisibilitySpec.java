package com.navneet.agentteam.auth.security;

import com.navneet.agentteam.auth.entity.AppUser;
import com.navneet.agentteam.auth.entity.OrgMember;
import com.navneet.agentteam.auth.repository.ProjectMemberRepository;
import com.navneet.agentteam.state.entity.PipelineRun;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds JPA Specifications for run visibility based on the current user's role.
 */
@Component
@RequiredArgsConstructor
public class RunVisibilitySpec {

    private final ProjectMemberRepository projectMemberRepo;

    /**
     * Returns a Specification that filters pipeline_run rows to only those
     * the given user is allowed to see.
     */
    public Specification<PipelineRun> forUser(AppUser user, OrgMember orgMember) {
        return (Root<PipelineRun> root, CriteriaQuery<?> query, CriteriaBuilder cb) -> {

            // SUPER_ADMIN sees everything
            if ("SUPER_ADMIN".equals(user.getGlobalRole())) {
                return cb.conjunction();
            }

            // ORG_ADMIN or BILLING_ADMIN — see all runs in their org
            if (orgMember != null &&
                ("ORG_ADMIN".equals(orgMember.getOrgRole()) ||
                 "BILLING_ADMIN".equals(orgMember.getOrgRole()))) {
                return cb.equal(root.get("orgId"), user.getOrgId());
            }

            // DEVELOPER — see runs in assigned projects only
            if (orgMember != null && "DEVELOPER".equals(orgMember.getOrgRole())) {
                List<String> projectIds = projectMemberRepo.findProjectIdsByUserId(user.getId());
                if (projectIds.isEmpty()) {
                    return cb.disjunction(); // no projects → no runs
                }
                return root.get("projectId").in(projectIds);
            }

            // FREE user — see own runs only
            return cb.equal(root.get("createdByUserId"), user.getId());
        };
    }
}
