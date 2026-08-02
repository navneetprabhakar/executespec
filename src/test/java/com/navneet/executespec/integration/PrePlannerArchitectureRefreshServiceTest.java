package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.codeintel.ProjectArchitectureJobService;
import com.navneet.executespec.codeintel.StalenessService;
import com.navneet.executespec.codeintel.StalenessService.StalenessReport;
import com.navneet.executespec.router.LlmProvider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrePlannerArchitectureRefreshServiceTest {

  private StalenessService stalenessService;
  private ProjectArchitectureJobService architectureJobs;
  private PrePlannerArchitectureRefreshService service;

  @BeforeEach
  void setUp() {
    stalenessService = mock(StalenessService.class);
    architectureJobs = mock(ProjectArchitectureJobService.class);
    service = new PrePlannerArchitectureRefreshService(stalenessService, architectureJobs);
  }

  @Test
  void freshProjectDoesNotAdmitArchitectureJob() {
    when(stalenessService.report("project-1", "pat"))
        .thenReturn(new StalenessReport(false, 0, 1, 0, 0, true, List.of()));

    assertThat(service.refreshIfStale("run-1", "project-1", "user-1", "pat", LlmProvider.ANTHROPIC))
        .isFalse();

    verify(architectureJobs, never())
        .requestWithProvider("project-1", "user-1", null, LlmProvider.ANTHROPIC);
    verify(stalenessService, never()).invalidate("project-1");
  }

  @Test
  void staleProjectAdmitsPinnedRouteWithoutWaitingOrInvalidating() {
    String runId = UUID.randomUUID().toString();
    when(stalenessService.report("project-1", "pat"))
        .thenReturn(new StalenessReport(true, 2, 2, 0, 0, true, List.of()));

    assertThat(service.refreshIfStale(runId, "project-1", "user-1", "pat", LlmProvider.GEMINI))
        .isTrue();

    verify(architectureJobs).requestWithProvider("project-1", "user-1", null, LlmProvider.GEMINI);
    verify(stalenessService, never()).invalidate("project-1");
  }
}
