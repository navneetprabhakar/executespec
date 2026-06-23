package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.CreateSessionRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.RepoContext;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.auth.service.AiProviderPreferenceService;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.router.ModelAccessService;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AgentSessionCreationServiceTest {

  @Mock AgentSessionRepository sessionRepo;
  @Mock ProjectAccessGuard projectAccessGuard;
  @Mock RunAccessGuard runAccessGuard;
  @Mock AiProviderPreferenceService aiProviderPreferenceService;
  @Mock ModelAccessService modelAccessService;

  private AgentSessionCreationService service;

  @BeforeEach
  void setUp() {
    service =
        new AgentSessionCreationService(
            sessionRepo,
            projectAccessGuard,
            runAccessGuard,
            aiProviderPreferenceService,
            modelAccessService);
    lenient().when(sessionRepo.save(any(AgentSession.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void returnsExistingRequestedSessionForSameUserWithoutSaving() {
    AgentSession existing = session("requested-session", "u1");
    when(sessionRepo.findById("requested-session")).thenReturn(Optional.of(existing));

    AgentSession result =
        service.create(
            new CreateSessionRequest(
                "requested-session",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null),
            user(),
            "org-1");

    assertThat(result).isSameAs(existing);
    verify(sessionRepo, never()).save(any(AgentSession.class));
  }

  @Test
  void rejectsExistingRequestedSessionForDifferentUser() {
    when(sessionRepo.findById("requested-session"))
        .thenReturn(Optional.of(session("requested-session", "other-user")));

    assertThatThrownBy(
            () ->
                service.create(
                    new CreateSessionRequest(
                        "requested-session",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null),
                    user(),
                    "org-1"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("forbidden");
  }

  @Test
  void persistsRunScopedSessionWithValidatedProjectAndProviderPreference() {
    PipelineRun run = PipelineRun.builder().runId("run-1").projectId("p-from-run").build();
    when(runAccessGuard.requireRunAccess(eq("run-1"), any(AppUserDetails.class))).thenReturn(run);
    when(aiProviderPreferenceService.updatePreferredProvider(
            any(AppUserDetails.class), eq("DEEPSEEK")))
        .thenReturn("DEEPSEEK");

    AgentSession result =
        service.create(
            new CreateSessionRequest(
                null,
                null,
                null,
                "root-hash",
                new RepoContext(
                    "repo",
                    "git@github.com:navneet/repo.git",
                    "main",
                    java.util.List.of("README.md"),
                    java.util.List.of("java")),
                "web",
                "Run agent",
                "DEEPSEEK",
                "deepseek-v4-flash-native",
                "production",
                "confirm_mutations",
                "run-1"),
            user(),
            "org-1");

    assertThat(result.getRunId()).isEqualTo("run-1");
    assertThat(result.getProjectId()).isEqualTo("p-from-run");
    assertThat(result.getClientSurface()).isEqualTo("WEB");
    assertThat(result.getDefaultExecutionMode()).isEqualTo("PRODUCTION");
    assertThat(result.getApprovalPolicy()).isEqualTo("CONFIRM_MUTATIONS");
    assertThat(result.getDefaultProvider()).isEqualTo("DEEPSEEK");
    assertThat(result.getRepoRemote()).isEqualTo("git@github.com:navneet/repo.git");
    verify(modelAccessService).assertModelAllowed("u1", "deepseek-v4-flash-native");

    var saved = ArgumentCaptor.forClass(AgentSession.class);
    verify(sessionRepo).save(saved.capture());
    assertThat(saved.getValue().getRunId()).isEqualTo("run-1");
    assertThat(saved.getValue().getProjectId()).isEqualTo("p-from-run");
  }

  @Test
  void rejectsProjectIdThatDoesNotMatchScopedRun() {
    PipelineRun run = PipelineRun.builder().runId("run-1").projectId("p-from-run").build();
    when(runAccessGuard.requireRunAccess(eq("run-1"), any(AppUserDetails.class))).thenReturn(run);

    assertThatThrownBy(
            () ->
                service.create(
                    new CreateSessionRequest(
                        null,
                        "other-project",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "run-1"),
                    user(),
                    "org-1"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("projectId does not match run");
  }

  private static AgentSession session(String id, String userId) {
    return AgentSession.builder()
        .id(id)
        .orgId("org-1")
        .userId(userId)
        .clientSurface("CLI")
        .title("Existing")
        .build();
  }

  private static AppUserDetails user() {
    AppUser user = new AppUser();
    user.setId("u1");
    user.setOrgId("org-1");
    user.setGlobalRole("USER");
    user.setTier("PRO");
    user.setEmail("u1@example.com");
    return new AppUserDetails(user, null);
  }
}
