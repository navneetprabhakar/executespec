package com.navneet.executespec.specchat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

import java.util.List;
import java.util.Map;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.AiProviderPreferenceService;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import jakarta.servlet.http.HttpServletRequest;

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

    SpecChatController controller = new SpecChatController(audit, service, flags, providers, access);
    var request =
        new SpecChatController.SpecChatRequest(
            "session-1", "foreign", null, List.of(Map.of("role", "user", "content", "list")), null);

    assertThatThrownBy(() -> controller.chat(user(), request, null, mock(HttpServletRequest.class)))
        .isSameAs(forbidden);
    verify(audit, never()).findBySessionId("session-1");
    verify(service, never())
        .runTurn(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString());
  }

  private static AppUserDetails user() {
    AppUser appUser =
        AppUser.builder().id("user-1").email("user@example.com").firstName("Test").orgId("org-1")
            .globalRole("USER").status("ACTIVE").build();
    return new AppUserDetails(
        appUser, OrgMember.builder().id("member-1").userId("user-1").orgId("org-1").build());
  }
}
