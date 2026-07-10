package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ClientCapabilities;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.StreamTurnRequest;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.AiProviderPreferenceService;
import com.navneet.executespec.router.ModelAccessService;
import com.navneet.executespec.router.ModelRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentStreamTurnStarterTest {

  @Mock AgentTurnRepository turnRepo;
  @Mock AgentActionEventRepository eventRepo;
  @Mock AgentSessionRepository sessionRepo;
  @Mock AiProviderPreferenceService aiProviderPreferenceService;
  @Mock ModelAccessService modelAccessService;
  @Mock ModelRegistry modelRegistry;

  private AgentStreamTurnStarter starter;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new ObjectMapper();
    AgentEventRecorder eventRecorder = new AgentEventRecorder(eventRepo, objectMapper);
    starter =
        new AgentStreamTurnStarter(
            new AgentIntentService(),
            new AgentTurnRecorder(turnRepo, objectMapper),
            eventRecorder,
            new AgentTracePayloadFactory(),
            sessionRepo,
            aiProviderPreferenceService,
            modelAccessService,
            modelRegistry);
    lenient().when(turnRepo.save(any(AgentTurn.class))).thenAnswer(inv -> inv.getArgument(0));
    lenient().when(eventRepo.save(any(AgentActionEvent.class))).thenAnswer(inv -> inv.getArgument(0));
    lenient().when(sessionRepo.save(any(AgentSession.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void startsStreamTurnPersistsTraceAndUpdatesProviderOverride() {
    AgentSession session = session();
    when(turnRepo.countBySessionId("s1")).thenReturn(2L);
    when(aiProviderPreferenceService.updatePreferredProvider(
            any(AppUserDetails.class), eq("DEEPSEEK")))
        .thenReturn("DEEPSEEK");

    var ctx =
        starter.begin(
            session,
            "s1",
            new StreamTurnRequest(
                "Find the auth bug",
                List.of(),
                null,
                new ClientCapabilities(false, true, true, true),
                "DEEPSEEK"),
            user());

    assertThat(ctx.sessionId()).isEqualTo("s1");
    assertThat(ctx.sequence()).isEqualTo(3);
    assertThat(ctx.provider()).isEqualTo("DEEPSEEK");
    assertThat(ctx.projectId()).isEqualTo("p1");
    assertThat(ctx.runId()).isEqualTo("run-session");
    assertThat(ctx.decision().route()).isEqualTo("READ_ONLY_CODE_HELP");
    assertThat(session.getDefaultProvider()).isEqualTo("DEEPSEEK");
    assertThat(session.getTitle()).isEqualTo("Find the auth bug");
    verify(sessionRepo).save(session);

    var turn = ArgumentCaptor.forClass(AgentTurn.class);
    verify(turnRepo).save(turn.capture());
    assertThat(turn.getValue().getStatus()).isEqualTo("STREAMING");
    assertThat(turn.getValue().getIntentType()).isEqualTo("READ_ONLY_CODE_HELP");
    assertThat(turn.getValue().getSequence()).isEqualTo(3);

    var event = ArgumentCaptor.forClass(AgentActionEvent.class);
    verify(eventRepo).save(event.capture());
    assertThat(event.getValue().getType()).isEqualTo("STREAM_STARTED");
    assertThat(event.getValue().getTurnId()).isEqualTo(ctx.turnId());
    assertThat(event.getValue().getPayloadJson()).contains("READ_ONLY_CODE_HELP");
    assertThat(event.getValue().getPayloadJson()).contains("runApiAllowed");
    assertThat(event.getValue().getPayloadJson()).contains("false");
  }

  @Test
  void projectScopedStreamCodeRequestEmitsConfirmationGatedCloudRunProposal() {
    AgentSession session = session();
    session.setRunId(null);
    when(turnRepo.countBySessionId("s1")).thenReturn(0L);
    when(aiProviderPreferenceService.preferredProvider(any(AppUserDetails.class))).thenReturn(null);

    var ctx =
        starter.begin(
            session,
            "s1",
            new StreamTurnRequest(
                "Submit a coding run to add README launch notes. Stop at the plan gate and do not"
                    + " code until I approve.",
                List.of(),
                null,
                new ClientCapabilities(false, true, true, true),
                null),
            user());

    assertThat(ctx.projectId()).isEqualTo("p1");
    assertThat(ctx.runId()).isNull();
    assertThat(ctx.decision().route()).isEqualTo("CODE_RUN_REQUIRED");
    assertThat(ctx.decision().requiresUserConfirmation()).isTrue();
    assertThat(ctx.decision().policy().runApiAllowed()).isTrue();
    assertThat(ctx.decision().proposedAction()).isNotNull();
    assertThat(ctx.decision().proposedAction().type()).isEqualTo("create_cloud_run");
    assertThat(ctx.decision().proposedAction().arguments())
        .containsEntry("projectId", "p1")
        .containsEntry(
            "spec",
            "Submit a coding run to add README launch notes. Stop at the plan gate and do not"
                + " code until I approve.");

    var turn = ArgumentCaptor.forClass(AgentTurn.class);
    verify(turnRepo).save(turn.capture());
    assertThat(turn.getValue().getStatus()).isEqualTo("STREAMING");
    assertThat(turn.getValue().getIntentType()).isEqualTo("CODE_RUN_REQUIRED");
    assertThat(turn.getValue().getActionType()).isEqualTo("create_cloud_run");

    var event = ArgumentCaptor.forClass(AgentActionEvent.class);
    verify(eventRepo).save(event.capture());
    assertThat(event.getValue().getPayloadJson()).contains("CODE_RUN_REQUIRED");
    assertThat(event.getValue().getPayloadJson()).contains("create_cloud_run");
    assertThat(event.getValue().getPayloadJson()).contains("runApiAllowed");
    assertThat(event.getValue().getPayloadJson()).contains("true");
  }

  @Test
  void fallsBackToSessionDefaultProviderWhenPreferenceIsMissing() {
    AgentSession session = session();
    session.setTitle("Existing title");
    session.setDefaultProvider("ANTHROPIC");
    when(turnRepo.countBySessionId("s1")).thenReturn(0L);
    when(aiProviderPreferenceService.preferredProvider(any(AppUserDetails.class))).thenReturn(null);

    var ctx =
        starter.begin(session, "s1", new StreamTurnRequest("", null, null, null, null), user());

    assertThat(ctx.provider()).isEqualTo("ANTHROPIC");
    assertThat(session.getDefaultProvider()).isEqualTo("ANTHROPIC");
    assertThat(session.getTitle()).isEqualTo("Existing title");
    assertThat(ctx.sequence()).isEqualTo(1);
  }

  @Test
  void explicitModelSwitchIsAuthorizedAndPersistsProviderAndModel() {
    AgentSession session = session();
    when(turnRepo.countBySessionId("s1")).thenReturn(0L);
    when(aiProviderPreferenceService.updatePreferredProvider(any(AppUserDetails.class), eq("OPENAI")))
        .thenReturn("OPENAI");
    when(modelRegistry.providerFor("gpt-5.4-mini")).thenReturn("openai");
    when(modelRegistry.canonicalize("gpt-5.4-mini")).thenReturn("gpt-5.4-mini");

    var ctx =
        starter.begin(
            session,
            "s1",
            new StreamTurnRequest(
                "Use the faster model", null, null, null, "OPENAI", "gpt-5.4-mini", null),
            user());

    verify(modelAccessService).assertModelAllowed("u1", "gpt-5.4-mini");
    assertThat(ctx.provider()).isEqualTo("OPENAI");
    assertThat(ctx.model()).isEqualTo("gpt-5.4-mini");
    assertThat(session.getDefaultProvider()).isEqualTo("OPENAI");
    assertThat(session.getDefaultModel()).isEqualTo("gpt-5.4-mini");
  }

  @Test
  void rejectsModelProviderMismatchBeforePersistingSession() {
    AgentSession session = session();
    when(turnRepo.countBySessionId("s1")).thenReturn(0L);
    when(modelRegistry.providerFor("gpt-5.4-mini")).thenReturn("openai");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                starter.begin(
                    session,
                    "s1",
                    new StreamTurnRequest(
                        "Switch", null, null, null, "ANTHROPIC", "gpt-5.4-mini", null),
                    user()))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("not served by provider");
    org.mockito.Mockito.verifyNoInteractions(sessionRepo);
  }

  private static AgentSession session() {
    return AgentSession.builder()
        .id("s1")
        .orgId("org-1")
        .userId("u1")
        .projectId("p1")
        .runId("run-session")
        .clientSurface("CLI")
        .title("New ExecuteSpec session")
        .defaultModel("deepseek-v4-flash-native")
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
