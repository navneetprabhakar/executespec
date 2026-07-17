package com.navneet.executespec.specchat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import com.navneet.executespec.agentsession.AgentIntentService;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.AiProviderPreferenceService;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Regression coverage for project-scoped spec chat tenant isolation. */
class SpecChatControllerAccessTest {

  @Test
  void rejectsForeignProjectBeforeAuditOrAgentLoop() {
    SpecChatAuditRepository audit = mock(SpecChatAuditRepository.class);
    SpecChatService service = mock(SpecChatService.class);
    FeatureFlagService flags = mock(FeatureFlagService.class);
    AiProviderPreferenceService providers = mock(AiProviderPreferenceService.class);
    ProjectAccessGuard access = mock(ProjectAccessGuard.class);
    ResponseStatusException forbidden =
        new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "forbidden");
    org.mockito.Mockito.doThrow(forbidden)
        .when(access)
        .assertCanWrite(org.mockito.ArgumentMatchers.eq("foreign"), any(AppUserDetails.class));

    SpecChatController controller =
        new SpecChatController(
            audit,
            service,
            flags,
            providers,
            access,
            mock(AgentIntentService.class),
            new com.navneet.executespec.security.InputSanitizer(),
            new com.navneet.executespec.security.ContextSanitizer(),
            mock(ChatSecurityAuditService.class));
    var request =
        new SpecChatController.SpecChatRequest(
            "session-1", "foreign", null, List.of(Map.of("role", "user", "content", "list")), null);

    assertThatThrownBy(() -> controller.chat(user(), request, null, mock(HttpServletRequest.class)))
        .isSameAs(forbidden);
    verify(audit, never()).findBySessionId("session-1");
    verify(service, never())
        .runTurn(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void cannotReuseAnotherUsersLegacySessionId() {
    SpecChatAuditRepository audit = mock(SpecChatAuditRepository.class);
    SpecChatService service = mock(SpecChatService.class);
    FeatureFlagService flags = mock(FeatureFlagService.class);
    AiProviderPreferenceService providers = mock(AiProviderPreferenceService.class);
    when(audit.findBySessionId("shared-session"))
        .thenReturn(
            java.util.Optional.of(
                SpecChatAudit.builder()
                    .id("audit-1")
                    .sessionId("shared-session")
                    .userId("another-user")
                    .orgId("org-1")
                    .turnCount(1)
                    .build()));
    SpecChatController controller =
        new SpecChatController(
            audit,
            service,
            flags,
            providers,
            mock(ProjectAccessGuard.class),
            mock(AgentIntentService.class),
            new com.navneet.executespec.security.InputSanitizer(),
            new com.navneet.executespec.security.ContextSanitizer(),
            mock(ChatSecurityAuditService.class));
    var request =
        new SpecChatController.SpecChatRequest(
            "shared-session",
            null,
            null,
            List.of(Map.of("role", "user", "content", "hello")),
            null);

    assertThatThrownBy(() -> controller.chat(user(), request, null, mock(HttpServletRequest.class)))
        .isInstanceOf(ResponseStatusException.class)
        .matches(
            error ->
                ((ResponseStatusException) error).getStatusCode()
                    == org.springframework.http.HttpStatus.FORBIDDEN);
    verify(audit, never()).save(any());
    verify(service, never()).runTurn(any(), any(), any(), any(), any(), any(), any());
  }

  private static AppUserDetails user() {
    AppUser appUser =
        AppUser.builder()
            .id("user-1")
            .email("user@example.com")
            .firstName("Test")
            .orgId("org-1")
            .globalRole("USER")
            .status("ACTIVE")
            .build();
    return new AppUserDetails(
        appUser, OrgMember.builder().id("member-1").userId("user-1").orgId("org-1").build());
  }
}
