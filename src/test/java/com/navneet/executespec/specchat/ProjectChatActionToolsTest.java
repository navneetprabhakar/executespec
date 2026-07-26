package com.navneet.executespec.specchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.integration.PlanApprovalService;
import com.navneet.executespec.integration.RunLifecycleService;
import com.navneet.executespec.integration.RunOrchestrator;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.report.ValidationReportService;
import com.navneet.executespec.security.ContextSanitizer;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Security contract for the legacy model-driven action-tool surface. */
class ProjectChatActionToolsTest {

  private final RunOrchestrator runOrchestrator = mock(RunOrchestrator.class);
  private final RunLifecycleService runLifecycleService = mock(RunLifecycleService.class);
  private final PlanApprovalService planApprovalService = mock(PlanApprovalService.class);
  private final PipelineRunRepository pipelineRunRepo = mock(PipelineRunRepository.class);
  private final ValidationReportService validationReportService =
      mock(ValidationReportService.class);
  private final RunAccessGuard runAccessGuard = mock(RunAccessGuard.class);
  private final ProjectAccessGuard projectAccessGuard = mock(ProjectAccessGuard.class);
  private final AppUserDetails userDetails = mock(AppUserDetails.class);

  private final ProjectChatActionTools tools =
      new ProjectChatActionTools(
          runOrchestrator,
          runLifecycleService,
          planApprovalService,
          pipelineRunRepo,
          validationReportService,
          runAccessGuard,
          projectAccessGuard,
          new ContextSanitizer(),
          new ObjectMapper());

  @Test
  void bindsOnlyReadToolsWhenNoScopedSingleUseConfirmationExists() {
    var names =
        tools.bind(context("project-1")).stream()
            .map(tool -> tool.getToolDefinition().name())
            .toList();

    assertThat(names).containsExactly("list_recent_runs", "get_run_report");
    assertThat(names).doesNotContain("submit_run", "approve_plan", "reject_plan", "cancel_run");
    verifyNoInteractions(runOrchestrator, runLifecycleService);
  }

  @Test
  void projectScopedReadReauthorizesAtToolBoundary() {
    ResponseStatusException denied =
        new ResponseStatusException(HttpStatus.FORBIDDEN, "viewer denied");
    doThrow(denied).when(projectAccessGuard).assertCanRead("project-1", userDetails);
    var list =
        tools.bind(context("project-1")).stream()
            .filter(tool -> "list_recent_runs".equals(tool.getToolDefinition().name()))
            .findFirst()
            .orElseThrow();

    assertThatThrownBy(() -> list.call("{}")).isSameAs(denied);
  }

  private ProjectChatActionTools.ToolContext context(String projectId) {
    return new ProjectChatActionTools.ToolContext(
        userDetails, projectId, "org-1", "session-1", "deepseek", null);
  }
}
