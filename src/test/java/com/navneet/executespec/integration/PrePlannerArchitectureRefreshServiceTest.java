package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.codeintel.ProjectArchitectureJobService;
import com.navneet.executespec.codeintel.ProjectArchitectureService;
import com.navneet.executespec.codeintel.StalenessService;
import com.navneet.executespec.codeintel.StalenessService.StalenessReport;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.router.LlmProvider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrePlannerArchitectureRefreshServiceTest {

  private StalenessService stalenessService;
  private ProjectArchitectureJobService architectureJobs;
  private ProjectArchitectureService architectureService;
  private PrePlannerArchitectureRefreshService service;

  @BeforeEach
  void setUp() {
    stalenessService = mock(StalenessService.class);
    architectureJobs = mock(ProjectArchitectureJobService.class);
    architectureService = mock(ProjectArchitectureService.class);
    service =
        new PrePlannerArchitectureRefreshService(
            stalenessService, architectureJobs, architectureService);
  }

  @Test
  void freshProjectDoesNotAdmitArchitectureJob() {
    when(stalenessService.report("project-1", "pat"))
        .thenReturn(new StalenessReport(false, 0, 1, 0, 0, true, List.of()));

    assertThat(service.refreshIfStale("run-1", "project-1", "user-1", "pat", LlmProvider.ANTHROPIC))
        .isFalse();

    verify(architectureJobs, never())
        .requestWithProviderAndAwaitArchitectureId(
            "project-1", "user-1", null, LlmProvider.ANTHROPIC);
    verify(stalenessService, never()).invalidate("project-1");
  }

  @Test
  void staleProjectWaitsForPinnedRouteAndInvalidatesOnlyAfterPromotedResultIsReadable() {
    String runId = UUID.randomUUID().toString();
    UUID architectureId = UUID.randomUUID();
    ProjectArchitectureDoc promoted = mock(ProjectArchitectureDoc.class);
    when(stalenessService.report("project-1", "pat"))
        .thenReturn(new StalenessReport(true, 2, 2, 0, 0, true, List.of()));
    when(architectureJobs.requestWithProviderAndAwaitArchitectureId(
            "project-1", "user-1", null, LlmProvider.GEMINI))
        .thenReturn(architectureId);
    when(architectureService.findById(architectureId)).thenReturn(Optional.of(promoted));

    assertThat(service.refreshIfStale(runId, "project-1", "user-1", "pat", LlmProvider.GEMINI))
        .isTrue();

    verify(architectureJobs)
        .requestWithProviderAndAwaitArchitectureId("project-1", "user-1", null, LlmProvider.GEMINI);
    verify(architectureService).findById(architectureId);
    verify(stalenessService).invalidate("project-1");
  }

  @Test
  void missingPromotedResultFailsClosedWithoutInvalidatingStaleness() {
    UUID architectureId = UUID.randomUUID();
    when(stalenessService.report("project-1", null))
        .thenReturn(new StalenessReport(true, 1, 1, 0, 0, true, List.of()));
    when(architectureJobs.requestWithProviderAndAwaitArchitectureId(
            "project-1", "user-1", null, LlmProvider.OPENAI))
        .thenReturn(architectureId);
    when(architectureService.findById(architectureId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service.refreshIfStale("run-1", "project-1", "user-1", null, LlmProvider.OPENAI))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Promoted pre-planner architecture is unavailable");

    verify(stalenessService, never()).invalidate("project-1");
  }
}
