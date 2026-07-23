package com.navneet.executespec.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * RR-008 — provider-scoped credential updates must refetch, write narrowly, and audit atomically.
 */
class UserSettingsUpdateServiceTest {

  private AppUserRepository userRepo;
  private ActivityAuditService auditService;
  private UserSettingsUpdateService service;

  @BeforeEach
  void setUp() {
    userRepo = mock(AppUserRepository.class);
    auditService = mock(ActivityAuditService.class);
    when(userRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    service = new UserSettingsUpdateService(userRepo, auditService);
  }

  @Test
  void updateGitHubRefetchesUnderLockAndDoesNotClobberUnrelatedCredentials() {
    // RR-008 — the fresh DB row carries an AI key set by another path. A GitHub-scoped save must
    // read that current row (findByIdForUpdate) and write ONLY the GitHub fields, so it cannot
    // revert the unrelated credential the way a stale whole-entity save did.
    AppUser current =
        AppUser.builder().id("u1").openAiApiKey("sk-openai-live").githubPat("old-pat").build();
    when(userRepo.findByIdForUpdate("u1")).thenReturn(Optional.of(current));

    AppUser saved = service.updateGitHub("u1", "new-pat", "acme");

    verify(userRepo).findByIdForUpdate("u1");
    assertThat(saved.getGithubPat()).isEqualTo("new-pat");
    assertThat(saved.getGithubOwner()).isEqualTo("acme");
    assertThat(saved.getOpenAiApiKey()).isEqualTo("sk-openai-live");
    verify(userRepo).save(current);

    ArgumentCaptor<ActivityAudit> audit = ArgumentCaptor.forClass(ActivityAudit.class);
    verify(auditService).record(audit.capture());
    assertThat(audit.getValue().getEventType()).isEqualTo("USER_GITHUB_SETTINGS_CHANGED");
    assertThat(audit.getValue().getNewValue()).isEqualTo("{\"hasToken\":true,\"owner\":\"acme\"}");
  }

  @Test
  void updateAiKeysPatchesOnlyProvidedProviders() {
    // RR-008 — patch semantics on the fresh row: a blank field leaves the stored key intact.
    AppUser current =
        AppUser.builder()
            .id("u1")
            .openAiApiKey("sk-openai-existing")
            .anthropicApiKey("sk-ant-existing")
            .build();
    when(userRepo.findByIdForUpdate("u1")).thenReturn(Optional.of(current));

    AppUser saved = service.updateAiKeys("u1", "  ", "sk-ant-new", null, " sk-ds-new ", "");

    assertThat(saved.getOpenAiApiKey()).isEqualTo("sk-openai-existing");
    assertThat(saved.getAnthropicApiKey()).isEqualTo("sk-ant-new");
    assertThat(saved.getDeepSeekApiKey()).isEqualTo("sk-ds-new");
    verify(auditService).record(any(ActivityAudit.class));
  }

  @Test
  void revokeAiKeyClearsOnlyTheNamedProviderAndReportsPresence() {
    AppUser current =
        AppUser.builder()
            .id("u1")
            .geminiApiKey("gemini-key")
            .anthropicApiKey("sk-ant-keep")
            .build();
    when(userRepo.findByIdForUpdate("u1")).thenReturn(Optional.of(current));

    UserSettingsUpdateService.RevokeResult result = service.revokeAiKey("u1", "gemini");

    assertThat(result.existed()).isTrue();
    assertThat(result.user().getGeminiApiKey()).isNull();
    assertThat(result.user().getAnthropicApiKey()).isEqualTo("sk-ant-keep");

    ArgumentCaptor<ActivityAudit> audit = ArgumentCaptor.forClass(ActivityAudit.class);
    verify(auditService).record(audit.capture());
    assertThat(audit.getValue().getNewValue())
        .isEqualTo("{\"provider\":\"gemini\",\"previouslyPresent\":true}");
  }

  @Test
  void missingUserIsRejectedWithoutMutationOrAudit() {
    when(userRepo.findByIdForUpdate("ghost")).thenReturn(Optional.empty());

    org.junit.jupiter.api.Assertions.assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> service.updateJira("ghost", "https://jira", "e@x.com", "tok"));

    verify(userRepo, never()).save(any());
    verify(auditService, never()).record(any());
  }
}
