package com.navneet.executespec.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.auth.dto.ProjectDto;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.ProjectService;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.junit.jupiter.api.Test;

/**
 * RL-2026-05-056 — regression for the cross-org invitee visibility hole. A user whose account has
 * no home org (orgId == null — the documented free-tier "no org" state, or a user removed from
 * their only org) could accept a project invite and even open the project by URL, but the project
 * never appeared in their list: {@code listProjects} short-circuited with an empty list before the
 * cross-org {@code ProjectMember} union (added in RL-2026-05-044) could run. The controller must
 * always delegate to {@link ProjectService#listProjectsForUser}, which honours their membership.
 */
class ProjectControllerTest {

  @Test
  void listProjects_nullOrg_stillReturnsCrossOrgMemberProjects() {
    ProjectService svc = mock(ProjectService.class);
    ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    ProjectController ctrl = new ProjectController(svc, guard);

    AppUser user = AppUser.builder().id("u-1").orgId(null).globalRole("USER").build();
    AppUserDetails principal = new AppUserDetails(user, null);

    ProjectDto shared =
        ProjectDto.from(
            Project.builder()
                .id("p-shared")
                .orgId("org-2")
                .name("Shared")
                .createdBy("u-x")
                .build());
    when(svc.listProjectsForUser(null, "u-1", false)).thenReturn(List.of(shared));

    var resp = ctrl.listProjects(principal);

    // The bug returned an empty list here; the fix delegates to the cross-org-aware service.
    assertThat(resp.getBody()).extracting("id").containsExactly("p-shared");
    verify(svc).listProjectsForUser(null, "u-1", false);
  }
}
