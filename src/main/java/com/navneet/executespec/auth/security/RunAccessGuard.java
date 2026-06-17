package com.navneet.executespec.auth.security;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * Centralised access-control guard for single-run endpoints.
 *
 * <p>Mirrors the row-level logic in {@link RunVisibilitySpec} but operates on a single {@link
 * PipelineRun} instead of a JPA {@code Specification}.
 *
 * <h3>Role hierarchy</h3>
 *
 * <ul>
 *   <li><b>SUPER_ADMIN</b> — unrestricted access to every run
 *   <li><b>ORG_ADMIN / BILLING_ADMIN</b> — runs that belong to the same org
 *   <li><b>DEVELOPER</b> — all runs in their org (projects are shared grouping tools)
 *   <li><b>FREE user</b> — own runs only
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RunAccessGuard {

  private final StateStore stateStore;
  private final ProjectMemberRepository projectMemberRepo;

  /**
   * Loads the run and asserts the caller may access it.
   *
   * @return the {@link PipelineRun} if access is granted
   * @throws ResponseStatusException 404 if the run does not exist, 401 if unauthenticated, 403 if
   *     the user has no visibility
   */
  public PipelineRun requireRunAccess(String runId, AppUserDetails userDetails) {
    PipelineRun run =
        stateStore
            .findRun(runId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found: " + runId));
    assertAccess(run, userDetails);
    return run;
  }

  /**
   * Asserts the caller may MUTATE the given run. Stricter than {@link #assertAccess}: only
   * SUPER_ADMIN, ORG_ADMIN, or the run's owner are admitted. Org developers / viewers who can READ
   * a run still need owner status to mutate its derived state (e.g. mark a test result invalid,
   * retry a task). Audit 2026-05-12 review item B #18.
   */
  public PipelineRun requireRunWriteAccess(String runId, AppUserDetails userDetails) {
    PipelineRun run = requireRunAccess(runId, userDetails);
    assertWriteAccess(run, userDetails);
    return run;
  }

  /** Same as {@link #requireRunWriteAccess} but on a pre-loaded run. */
  public void assertWriteAccess(PipelineRun run, AppUserDetails userDetails) {
    AppUser user = userDetails.getUser();
    if ("SUPER_ADMIN".equals(user.getGlobalRole())) return;

    OrgMember orgMember = userDetails.getOrgMember();
    if (orgMember != null && "ORG_ADMIN".equals(orgMember.getOrgRole())) {
      if (user.getOrgId() != null && user.getOrgId().equals(run.getOrgId())) return;
      throw forbidden(run);
    }

    if (isOwner(user, run)) return;

    throw new ResponseStatusException(
        HttpStatus.FORBIDDEN,
        "Only the run owner or an ORG_ADMIN may modify run " + run.getRunId());
  }

  /** Asserts the caller may access the given (already-loaded) run. */
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
      if (isProjectMember(user, run)) return;
      throw forbidden(run);
    }

    // DEVELOPER — all runs in their org (projects are shared grouping tools)
    if (orgMember != null && "DEVELOPER".equals(orgMember.getOrgRole())) {
      if (user.getOrgId() != null && user.getOrgId().equals(run.getOrgId())) return;
      if (isProjectMember(user, run)) return;
      throw forbidden(run);
    }

    // Safety fallback: user has orgId but orgMember record is missing/orphaned.
    // Still grant org-scoped access so they can interact with runs in their org.
    if (user.getOrgId() != null) {
      if (user.getOrgId().equals(run.getOrgId())) return;
      if (isProjectMember(user, run)) return;
      throw forbidden(run);
    }

    // FREE user (no org) — own runs only
    if (isOwner(user, run)) return;
    if (isProjectMember(user, run)) return;

    throw forbidden(run);
  }

  /** Throws 401 if no authentication principal is present. */
  public void requireAuthenticated(AppUserDetails userDetails) {
    if (userDetails == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
    }
  }

  /** Asserts the caller is a SUPER_ADMIN. */
  public void requireSuperAdmin(AppUserDetails userDetails) {
    requireAuthenticated(userDetails);
    if (!"SUPER_ADMIN".equals(userDetails.getUser().getGlobalRole())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Super-admin access required");
    }
  }

  /** Asserts the caller is at least an ORG_ADMIN (or SUPER_ADMIN). */
  public void requireOrgAdmin(AppUserDetails userDetails) {
    requireAuthenticated(userDetails);
    if ("SUPER_ADMIN".equals(userDetails.getUser().getGlobalRole())) return;
    if (userDetails.getOrgMember() != null
        && "ORG_ADMIN".equals(userDetails.getOrgMember().getOrgRole())) return;
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Org-admin access required");
  }

  /** Returns the caller's org ID, or {@code null} for SUPER_ADMIN (global scope). */
  public String getOrgIdOrNull(AppUserDetails userDetails) {
    requireAuthenticated(userDetails);
    if ("SUPER_ADMIN".equals(userDetails.getUser().getGlobalRole())) return null;
    return userDetails.getUser().getOrgId();
  }

  private boolean isOwner(AppUser user, PipelineRun run) {
    return user.getId() != null && user.getId().equals(run.getCreatedByUserId());
  }

  private boolean isProjectMember(AppUser user, PipelineRun run) {
    return user.getId() != null
        && run.getProjectId() != null
        && projectMemberRepo.existsByProjectIdAndUserId(run.getProjectId(), user.getId());
  }

  private ResponseStatusException forbidden(PipelineRun run) {
    return new ResponseStatusException(
        HttpStatus.FORBIDDEN, "Access denied to run " + run.getRunId());
  }
}
