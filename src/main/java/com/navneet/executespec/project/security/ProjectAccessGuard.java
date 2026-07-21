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
 * <p>Read access remains organisation-first because projects are an organisation-visible grouping
 * surface. Write access is least-privilege: SUPER_ADMIN, the host organisation's ORG_ADMIN, or an
 * explicit PROJECT_ADMIN / PROJECT_DEVELOPER membership. Mere organisation membership is never a
 * project mutation capability.
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

  /**
   * Assert the caller may WRITE / MUTATE this project. Use this on every endpoint that creates,
   * updates, or deletes per-project state — invites, share links, memory compactions, autofix
   * dispatches, architecture (re)generation, override / exception submissions, credential storage,
   * etc.
   *
   * <p>Audit 2026-05-12 review item B #9-#18 — closes the "{@code assertCanRead} used as
   * write-gate" cluster.
   */
  public Project assertCanWrite(String projectId, AppUserDetails userDetails) {
    Project project = assertCanRead(projectId, userDetails);

    if ("SUPER_ADMIN".equals(userDetails.getUser().getGlobalRole())) {
      return project;
    }

    String callerOrgId = userDetails.getUser().getOrgId();
    boolean sameOrg = callerOrgId != null && callerOrgId.equals(project.getOrgId());
    if (sameOrg && isOrgAdmin(userDetails)) {
      return project;
    }

    String callerUserId = userDetails.getUser().getId();
    if (callerUserId != null) {
      var member = projectMemberRepo.findByProjectIdAndUserId(projectId, callerUserId);
      if (member.isPresent()) {
        String role = member.get().getProjectRole();
        if ("PROJECT_VIEWER".equals(role)) {
          throw new ResponseStatusException(
              HttpStatus.FORBIDDEN, "PROJECT_VIEWER cannot modify this project");
        }
        if ("PROJECT_ADMIN".equals(role) || "PROJECT_DEVELOPER".equals(role)) {
          return project;
        }
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "Unknown project role cannot modify this project");
      }
    }

    throw new ResponseStatusException(
        HttpStatus.FORBIDDEN, "You don't have write access to this project");
  }

  /**
   * Assert the caller has an administrative capability for this project.
   *
   * <p>This is deliberately stronger than {@link #assertCanWrite}: project developers may mutate
   * ordinary project resources, but they must not read administrative activity such as policy and
   * auto-detection changes. The capability is granted only to SUPER_ADMIN, the host organisation's
   * ORG_ADMIN, or an explicit PROJECT_ADMIN member (including an authorised external admin).
   */
  public Project assertCanAdmin(String projectId, AppUserDetails userDetails) {
    Project project = assertCanRead(projectId, userDetails);

    if ("SUPER_ADMIN".equals(userDetails.getUser().getGlobalRole())) {
      return project;
    }

    String callerOrgId = userDetails.getUser().getOrgId();
    boolean sameOrg = callerOrgId != null && callerOrgId.equals(project.getOrgId());
    if (sameOrg && isOrgAdmin(userDetails)) {
      return project;
    }

    String callerUserId = userDetails.getUser().getId();
    if (callerUserId != null
        && projectMemberRepo
            .findByProjectIdAndUserId(projectId, callerUserId)
            .map(member -> "PROJECT_ADMIN".equals(member.getProjectRole()))
            .orElse(false)) {
      return project;
    }

    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project admin access is required");
  }

  private static boolean isOrgAdmin(AppUserDetails userDetails) {
    if ("ORG_ADMIN".equals(userDetails.getUser().getGlobalRole())) return true;
    return userDetails.getOrgMember() != null
        && "ORG_ADMIN".equals(userDetails.getOrgMember().getOrgRole());
  }
}
