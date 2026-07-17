package com.navneet.executespec.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.auth.entity.Project;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class ProjectExportServiceTest {

  @Mock ProjectExportJobStore store;

  private ProjectExportProperties properties;
  private ProjectExportService service;
  private Project project;

  @BeforeEach
  void setUp() {
    properties = new ProjectExportProperties();
    service = new ProjectExportService(store, properties, new SimpleMeterRegistry());
    project = Project.builder().id("project-1").orgId("org-1").name("Project").build();
  }

  @Test
  void idempotentReplayBypassesQuotaAndCreation() {
    var existing = ExportTestFixtures.job(ProjectExportJobStore.Status.SUCCEEDED);
    when(store.findByIdempotency("project-1", "user-1", "request-key"))
        .thenReturn(Optional.of(existing));

    assertThat(service.request(project, "user-1", "request-key")).isSameAs(existing);
    verify(store, never()).countCreatedSince(any(), any());
    verify(store, never()).createOrGet(any(), any(), any(), any(), any(), any());
  }

  @Test
  void activeProjectJobIsReusedAcrossAdminRequests() {
    var active = ExportTestFixtures.job(ProjectExportJobStore.Status.RUNNING);
    when(store.findByIdempotency("project-1", "user-2", "different-key"))
        .thenReturn(Optional.empty());
    when(store.findActive("project-1")).thenReturn(Optional.of(active));

    assertThat(service.request(project, "user-2", "different-key")).isSameAs(active);
  }

  @Test
  void dailyLimitFailsBeforeJobInsert() {
    properties.setMaxJobsPerProjectPerDay(2);
    when(store.findByIdempotency("project-1", "user-1", "request-key"))
        .thenReturn(Optional.empty());
    when(store.findActive("project-1")).thenReturn(Optional.empty());
    when(store.countCreatedSince(eq("project-1"), any())).thenReturn(2L);

    assertThatThrownBy(() -> service.request(project, "user-1", "request-key"))
        .isInstanceOf(ProjectExportService.ExportQuotaExceededException.class);
    verify(store, never()).createOrGet(any(), any(), any(), any(), any(), any());
  }

  @Test
  void invalidIdempotencyKeyFailsClosed() {
    assertThatThrownBy(() -> service.request(project, "user-1", "contains spaces"))
        .isInstanceOf(ProjectExportService.InvalidIdempotencyKeyException.class);
  }

  @Test
  void missingKeyGetsServerGeneratedValue() {
    when(store.findByIdempotency(eq("project-1"), eq("user-1"), any()))
        .thenReturn(Optional.empty());
    when(store.findActive("project-1")).thenReturn(Optional.empty());
    when(store.countCreatedSince(eq("project-1"), any())).thenReturn(0L);
    var created = ExportTestFixtures.job(ProjectExportJobStore.Status.QUEUED);
    when(store.createOrGet(any(), eq("project-1"), eq("org-1"), eq("user-1"), any(), any()))
        .thenReturn(created);

    assertThat(service.request(project, "user-1", null)).isSameAs(created);
    verify(store).createOrGet(any(), eq("project-1"), eq("org-1"), eq("user-1"), any(), any());
  }
}
