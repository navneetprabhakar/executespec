package com.navneet.executespec.sharing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.codeintel.repository.ProjectArchitectureDocRepository;
import com.navneet.executespec.codereview.repository.CodeReviewIssueRepository;
import com.navneet.executespec.codereview.repository.CodeReviewScanRepository;
import com.navneet.executespec.prreview.repository.PrReviewCommentRepository;
import com.navneet.executespec.prreview.repository.PrReviewRepository;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.sharing.controller.ProjectSharePublicController;
import com.navneet.executespec.sharing.entity.ProjectShareLink;
import com.navneet.executespec.sharing.service.ProjectShareLinkService;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ProjectSharePublicControllerTest {

  private ProjectShareLinkService linkService;
  private ProjectRepository projectRepo;
  private ProjectSharePublicController controller;

  @BeforeEach
  void setUp() {
    linkService = mock(ProjectShareLinkService.class);
    projectRepo = mock(ProjectRepository.class);
    controller =
        new ProjectSharePublicController(
            linkService,
            mock(PipelineRunRepository.class),
            mock(ValidationReportRepository.class),
            mock(CodeReviewScanRepository.class),
            mock(CodeReviewIssueRepository.class),
            mock(PrReviewRepository.class),
            mock(PrReviewCommentRepository.class),
            mock(ProjectArchitectureDocRepository.class),
            projectRepo);
  }

  @Test
  void shared_project_returns_public_project_snapshot_and_records_view() {
    Instant created = Instant.parse("2026-06-01T10:15:30Z");
    Instant updated = Instant.parse("2026-06-02T10:15:30Z");
    ProjectShareLink link = projectOverviewLink("tok-project", "p-1");
    link.setLabel("Migration snapshot");
    when(linkService.resolveActive("tok-project")).thenReturn(link);
    when(projectRepo.findById("p-1"))
        .thenReturn(
            Optional.of(
                Project.builder()
                    .id("p-1")
                    .orgId("org-1")
                    .name("Payments Platform")
                    .description("API migration")
                    .status("ACTIVE")
                    .aiProvider(LlmProvider.DEEPSEEK)
                    .githubOwner("executespec")
                    .createdBy("u-1")
                    .createdAt(created)
                    .updatedAt(updated)
                    .build()));

    ProjectSharePublicController.SharedProjectOverview body =
        controller.sharedProject("tok-project").getBody();

    assertNotNull(body);
    assertEquals("p-1", body.projectId());
    assertEquals("Payments Platform", body.name());
    assertEquals("API migration", body.description());
    assertEquals("ACTIVE", body.status());
    assertEquals("DEEPSEEK", body.aiProvider());
    assertEquals("executespec", body.githubOwner());
    assertEquals(created, body.createdAt());
    assertEquals(updated, body.updatedAt());
    assertEquals("Migration snapshot", body.label());
    verify(linkService).recordView(link);
  }

  @Test
  void shared_project_rejects_token_for_other_surface() {
    ProjectShareLink link =
        ProjectShareLink.builder()
            .id(UUID.randomUUID())
            .projectId("p-1")
            .token("tok-run")
            .surface(ProjectShareLink.Surface.RUN_DETAIL)
            .scopeId("run-1")
            .createdByUserId("u-1")
            .createdAt(Instant.now())
            .build();
    when(linkService.resolveActive("tok-run")).thenReturn(link);

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> controller.sharedProject("tok-run"));

    assertEquals(404, ex.getStatusCode().value());
  }

  private static ProjectShareLink projectOverviewLink(String token, String projectId) {
    return ProjectShareLink.builder()
        .id(UUID.randomUUID())
        .projectId(projectId)
        .token(token)
        .surface(ProjectShareLink.Surface.PROJECT_OVERVIEW)
        .createdByUserId("u-1")
        .createdAt(Instant.now())
        .build();
  }
}
