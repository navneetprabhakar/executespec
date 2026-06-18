package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ActionPlanRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ActionResultRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ClientCapabilities;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.agentsession.repository.AgentUsageLedgerRepository;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.auth.service.AiProviderPreferenceService;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.router.ModelAccessService;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentSessionServiceActionLifecycleTest {

  @Mock AgentSessionRepository sessionRepo;
  @Mock AgentTurnRepository turnRepo;
  @Mock AgentActionEventRepository eventRepo;
  @Mock AgentUsageLedgerRepository usageRepo;
  @Mock ProjectAccessGuard projectAccessGuard;
  @Mock RunAccessGuard runAccessGuard;
  @Mock ProjectRepository projectRepo;
  @Mock ProjectRepoRepository projectRepoRepo;
  @Mock ProjectRepoLinkRepository projectRepoLinkRepo;
  @Mock PipelineRunRepository pipelineRunRepo;
  @Mock AiProviderPreferenceService aiProviderPreferenceService;
  @Mock ModelAccessService modelAccessService;

  private AgentSessionService service;

  @BeforeEach
  void setUp() {
    service =
        new AgentSessionService(
            sessionRepo,
            turnRepo,
            eventRepo,
            usageRepo,
            new AgentIntentService(),
            new AgentActionCatalog(),
            projectAccessGuard,
            runAccessGuard,
            projectRepo,
            projectRepoRepo,
            projectRepoLinkRepo,
            pipelineRunRepo,
            new ObjectMapper(),
            aiProviderPreferenceService,
            modelAccessService);
    when(eventRepo.save(any(AgentActionEvent.class))).thenAnswer(inv -> inv.getArgument(0));
    lenient().when(sessionRepo.save(any(AgentSession.class))).thenAnswer(inv -> inv.getArgument(0));
    when(sessionRepo.findById("s1")).thenReturn(Optional.of(session()));
  }

  @Test
  void planActionRecordsPlannedEventAndReturnsSurfaceScopedCapabilities() throws Exception {
    var response =
        service.planAction(
            "s1",
            new ActionPlanRequest(
                "implement retries in the CLI",
                "p1",
                null,
                new ClientCapabilities(true, true, true, true),
                "VSCODE"),
            user());

    assertThat(response.sessionId()).isEqualTo("s1");
    assertThat(response.decision().intent()).isEqualTo("LOCAL_CODE_TASK");
    assertThat(response.capabilities()).extracting("name").contains("local_code_task");

    var saved = ArgumentCaptor.forClass(AgentActionEvent.class);
    org.mockito.Mockito.verify(eventRepo).save(saved.capture());
    assertThat(saved.getValue().getType()).isEqualTo("ACTION_PLANNED");
    assertThat(saved.getValue().getPayloadJson()).contains(response.actionId());
    assertThat(saved.getValue().getPayloadJson()).contains("VSCODE");
  }

  @Test
  void recordActionResultPersistsTouchedFilesAndVerification() {
    var response =
        service.recordActionResult(
            "s1",
            "a1",
            new ActionResultRequest(
                "failed",
                "missing expected artifact",
                "run-1",
                List.of("README.md"),
                Map.of("missingArtifacts", List.of("CODEMAP.md"), "passed", false)),
            user());

    assertThat(response.status()).isEqualTo("FAILED");
    assertThat(response.summary()).isEqualTo("missing expected artifact");

    var saved = ArgumentCaptor.forClass(AgentActionEvent.class);
    org.mockito.Mockito.verify(eventRepo).save(saved.capture());
    assertThat(saved.getValue().getType()).isEqualTo("ACTION_RESULT");
    assertThat(saved.getValue().getPayloadJson()).contains("README.md");
    assertThat(saved.getValue().getPayloadJson()).contains("CODEMAP.md");
    verify(sessionRepo).save(any(AgentSession.class));
  }

  private static AgentSession session() {
    return AgentSession.builder()
        .id("s1")
        .orgId("org-1")
        .userId("u1")
        .projectId("p1")
        .clientSurface("CLI")
        .title("test")
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
