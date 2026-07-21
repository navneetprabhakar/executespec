package com.navneet.executespec.specchat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ProjectChatConfirmationControllerTest {

  @Test
  void projectViewerCannotIssueConfirmationAuthority() {
    var service = mock(ProjectChatConfirmationService.class);
    var guard = mock(ProjectAccessGuard.class);
    var audit = mock(ChatSecurityAuditService.class);
    var controller = new ProjectChatConfirmationController(service, guard, audit);
    var principal =
        new AppUserDetails(
            AppUser.builder()
                .id("viewer-1")
                .orgId("org-1")
                .email("viewer@example.com")
                .globalRole("USER")
                .status("ACTIVE")
                .build(),
            null);
    var denied =
        new ResponseStatusException(
            HttpStatus.FORBIDDEN, "PROJECT_VIEWER cannot modify this project");
    org.mockito.Mockito.doThrow(denied).when(guard).assertCanWrite("project-1", principal);

    assertThatThrownBy(
            () ->
                controller.issue(
                    principal,
                    "project-1",
                    new ProjectChatConfirmationController.ConfirmationRequest(
                        "GENERATE_PROJECT_DOC",
                        "deepseek",
                        "deepseek-v4",
                        Map.of("docType", "SETUP"))))
        .isSameAs(denied);
    verify(service, never())
        .issue(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    verify(audit)
        .recordConfirmation(
            "CHAT_CONFIRMATION_ISSUE_DENIED",
            "project-1",
            null,
            principal,
            null,
            null,
            null,
            null,
            null,
            "DENIED");
  }
}
