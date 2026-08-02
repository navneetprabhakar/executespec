package com.navneet.executespec.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SandboxProvisioningServiceTest {

  private AppUserRepository users;
  private ProjectRepository projects;
  private ProjectMemberRepository members;
  private SandboxProvisioningService service;

  @BeforeEach
  void setUp() {
    users = mock(AppUserRepository.class);
    projects = mock(ProjectRepository.class);
    members = mock(ProjectMemberRepository.class);
    service =
        new SandboxProvisioningService(users, projects, members, mock(SampleSpecRegistry.class));
  }

  @Test
  void provisionLocksTheUserAndReusesOnlyItsActiveSandbox() {
    AppUser user = user("user-1", "org-1", "sandbox-1");
    Project sandbox = sandbox("sandbox-1", "user-1", "org-1", "ACTIVE", true);
    when(users.findByIdForUpdate("user-1")).thenReturn(Optional.of(user));
    when(projects.findById("sandbox-1")).thenReturn(Optional.of(sandbox));

    assertThat(service.provisionIfNeeded("user-1", null)).isEqualTo("sandbox-1");

    verify(users).findByIdForUpdate("user-1");
    verify(projects, never()).save(any());
    verify(members, never()).save(any());
  }

  @Test
  void archivedPointerIsNotReusedAndCreatesOneFreshSandbox() {
    AppUser user = user("user-1", "org-1", "sandbox-old");
    Project archived = sandbox("sandbox-old", "user-1", "org-1", "ARCHIVED", true);
    when(users.findByIdForUpdate("user-1")).thenReturn(Optional.of(user));
    when(projects.findById("sandbox-old")).thenReturn(Optional.of(archived));

    String newId = service.provisionIfNeeded("user-1", null);

    assertThat(newId).isNotEqualTo("sandbox-old");
    ArgumentCaptor<Project> project = ArgumentCaptor.forClass(Project.class);
    verify(projects).save(project.capture());
    assertThat(project.getValue().getId()).isEqualTo(newId);
    assertThat(project.getValue().isSandbox()).isTrue();
    assertThat(project.getValue().getStatus()).isEqualTo("ACTIVE");
    assertThat(user.getSandboxProjectId()).isEqualTo(newId);
    verify(members).save(any());
    verify(users).save(user);
  }

  @Test
  void foreignOrOrdinaryProjectPointerIsNeverReportedAsASandbox() {
    AppUser user = user("user-1", "org-1", "project-1");
    Project ordinary = sandbox("project-1", "user-2", "org-2", "ACTIVE", false);
    when(users.findById("user-1")).thenReturn(Optional.of(user));
    when(projects.findById("project-1")).thenReturn(Optional.of(ordinary));

    assertThat(service.currentSandboxId("user-1")).isNull();
  }

  @Test
  void statusReturnsOnlyAReusableSandbox() {
    AppUser user = user("user-1", "org-1", "sandbox-1");
    Project sandbox = sandbox("sandbox-1", "user-1", "org-1", "ACTIVE", true);
    when(users.findById("user-1")).thenReturn(Optional.of(user));
    when(projects.findById("sandbox-1")).thenReturn(Optional.of(sandbox));

    assertThat(service.currentSandboxId("user-1")).isEqualTo("sandbox-1");
  }

  private static AppUser user(String id, String orgId, String sandboxId) {
    return AppUser.builder()
        .id(id)
        .orgId(orgId)
        .email(id + "@example.test")
        .passwordHash("not-used")
        .sandboxProjectId(sandboxId)
        .build();
  }

  private static Project sandbox(
      String id, String createdBy, String orgId, String status, boolean isSandbox) {
    return Project.builder()
        .id(id)
        .orgId(orgId)
        .name("Sandbox")
        .createdBy(createdBy)
        .status(status)
        .isSandbox(isSandbox)
        .build();
  }
}
