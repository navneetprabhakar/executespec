package com.navneet.executespec.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SandboxCleanupJobTest {

  private ProjectRepository projects;
  private AppUserRepository users;
  private SandboxRunActivityRepository runs;
  private SandboxCleanupJob job;

  @BeforeEach
  void setUp() {
    projects = mock(ProjectRepository.class);
    users = mock(AppUserRepository.class);
    runs = mock(SandboxRunActivityRepository.class);
    job = new SandboxCleanupJob(projects, users, runs);
  }

  @Test
  void archivesAnIdleSandboxAndClearsOnlyItsMatchingUserPointer() {
    Project sandbox = oldSandbox("sandbox-1", "user-1");
    AppUser owner =
        AppUser.builder()
            .id("user-1")
            .email("user-1@example.test")
            .passwordHash("not-used")
            .sandboxProjectId("sandbox-1")
            .build();
    when(projects.findByIsSandboxTrueAndStatus("ACTIVE")).thenReturn(List.of(sandbox));
    when(runs.countByProjectIdAndStartedAtGreaterThanEqual(
            org.mockito.ArgumentMatchers.eq("sandbox-1"),
            org.mockito.ArgumentMatchers.any(Instant.class)))
        .thenReturn(0L);
    when(users.findById("user-1")).thenReturn(Optional.of(owner));

    job.cleanup();

    assertThat(sandbox.getStatus()).isEqualTo("ARCHIVED");
    assertThat(owner.getSandboxProjectId()).isNull();
    verify(projects).save(sandbox);
    verify(users).save(owner);
  }

  @Test
  void recentRunKeepsTheSandboxActive() {
    Project sandbox = oldSandbox("sandbox-1", "user-1");
    when(projects.findByIsSandboxTrueAndStatus("ACTIVE")).thenReturn(List.of(sandbox));
    when(runs.countByProjectIdAndStartedAtGreaterThanEqual(
            org.mockito.ArgumentMatchers.eq("sandbox-1"),
            org.mockito.ArgumentMatchers.any(Instant.class)))
        .thenReturn(1L);

    job.cleanup();

    assertThat(sandbox.getStatus()).isEqualTo("ACTIVE");
    verify(projects, never()).save(sandbox);
  }

  @Test
  void oldButNonTerminalRunKeepsTheSandboxActive() {
    Project sandbox = oldSandbox("sandbox-1", "user-1");
    when(projects.findByIsSandboxTrueAndStatus("ACTIVE")).thenReturn(List.of(sandbox));
    when(runs.countNonTerminalByProjectId("sandbox-1")).thenReturn(1L);

    job.cleanup();

    assertThat(sandbox.getStatus()).isEqualTo("ACTIVE");
    verify(runs, never())
        .countByProjectIdAndStartedAtGreaterThanEqual(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(Instant.class));
    verify(projects, never()).save(sandbox);
  }

  private static Project oldSandbox(String id, String createdBy) {
    Instant old = Instant.now().minus(2, ChronoUnit.DAYS);
    return Project.builder()
        .id(id)
        .orgId("org-1")
        .name("Sandbox")
        .createdBy(createdBy)
        .status("ACTIVE")
        .isSandbox(true)
        .createdAt(old)
        .updatedAt(old)
        .build();
  }
}
