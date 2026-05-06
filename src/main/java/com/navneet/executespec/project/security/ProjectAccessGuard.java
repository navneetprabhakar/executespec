package com.navneet.executespec.project.security;

import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.security.AppUserDetails;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * Single point of enforcement for "can caller read/write a given project" — closes the IDOR cluster
 * on project sub-resource endpoints (adversarial-test-report-2026-04-18 Finding 1).
 *
 * <p>Semantics match the pre-existing {@code ProjectRepoService.requireProjectAccess} pattern so we
 * don't break current callers: the caller must be a member of the project's organisation (their
 * {@code orgId} equals {@code project.orgId}). SUPER_ADMIN short-circuits any check.
 *
 * <p>Project-member-level granularity (e.g. PROJECT_DEVELOPER vs ORG_ADMIN) is a follow-up — the
 * current bar is "don't leak to strangers in unrelated orgs", which is what the adversarial test
 * exposed.
 */
@Component
@RequiredArgsConstructor
public class ProjectAccessGuard {

  private final ProjectRepository projectRepo;

  /**
   * V175 — consult {@code project_member} after the org-id check so external collaborators (added
   * via the new project-invite flow) get past the {@code "another organization"} 403. Existing
   * in-org reads short-circuit on the org match before this is even queried, so the only DB hit
   * here is for the cross-org case.
   */
  private final ProjectMemberRepository projectMemberRepo;

  /**
   * Load a project and assert the authenticated caller may read it. Throws 404 when the project
   * doesn't exist (avoids leaking existence to non-members) and 403 when the caller is
   * authenticated but unrelated to the project's organisation.
   *
   * <p>Callers that don't need the Project entity can discard the return value; the side effect is
   * the exception on denial.
   */
  public Project assertCanRead(String projectId, AppUserDetails userDetails) {
    if (projectId == null || projectId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectId is required");
    }
    if (userDetails == null || userDetails.getUser() == null) {
      // Should never happen — the filter chain requires auth — but fail closed.
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
    Project project =
        projectRepo
            .findById(projectId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

    if ("SUPER_ADMIN".equals(userDetails.getUser().getGlobalRole())) {
      return project;
    }

    String callerOrgId = userDetails.getUser().getOrgId();
    if (callerOrgId != null && callerOrgId.equals(project.getOrgId())) {
      return project;
    }

    // V175 — cross-org collaborators are admitted via project_member rows (typically created by
    // accepting a project_invite). The 403 only fires when neither org membership nor a
    // project_member row exists for this caller.
    String callerUserId = userDetails.getUser().getId();
    if (callerUserId != null
        && projectMemberRepo.existsByProjectIdAndUserId(projectId, callerUserId)) {
      return project;
    }

    throw new ResponseStatusException(
        HttpStatus.FORBIDDEN, "You don't have access to this project");
  }
}
