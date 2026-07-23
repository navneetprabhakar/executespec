package com.navneet.executespec.specchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.OrgModelOverrideService;
import com.navneet.executespec.router.ProjectProviderResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.router.RunModelOverrides;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ProjectChatConfirmationServiceTest {

  private final ProjectChatConfirmationRepository repository =
      mock(ProjectChatConfirmationRepository.class);
  private final ProjectChatConfirmedActionExecutor executor =
      mock(ProjectChatConfirmedActionExecutor.class);
  private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
  private final ProjectProviderResolver providerResolver = mock(ProjectProviderResolver.class);
  private final ModelResolver modelResolver = mock(ModelResolver.class);
  private final OrgModelOverrideService orgModelOverrides = mock(OrgModelOverrideService.class);
  private final ChatSecurityAuditService audit = mock(ChatSecurityAuditService.class);
  private final AtomicReference<ProjectChatConfirmation> stored = new AtomicReference<>();

  private Project project;
  private AppUserDetails principal;
  private ProjectChatConfirmationService service;

  @AfterEach
  void clearRouteContext() {
    RunModelOverrides.clear();
  }

  @BeforeEach
  void setUp() {
    project =
        Project.builder()
            .id("project-1")
            .orgId("org-1")
            .name("Project")
            .createdBy("user-1")
            .aiProvider(LlmProvider.DEEPSEEK)
            .build();
    principal = principal("user-1");
    when(guard.assertCanWrite(anyString(), any())).thenReturn(project);
    when(providerResolver.resolve(project)).thenReturn(LlmProvider.DEEPSEEK);
    when(orgModelOverrides.overridesForRun("org-1")).thenReturn(Map.of());
    when(modelResolver.resolve("ProjectDocGeneratorService", LlmProvider.DEEPSEEK))
        .thenReturn(
            ResolvedModel.builder()
                .requestedProvider(LlmProvider.DEEPSEEK)
                .actualProvider(LlmProvider.DEEPSEEK)
                .model("deepseek-v4")
                .agentTier(AgentTier.WORKHORSE)
                .build());
    when(repository.save(any(ProjectChatConfirmation.class)))
        .thenAnswer(
            invocation -> {
              ProjectChatConfirmation row = invocation.getArgument(0);
              stored.set(row);
              return row;
            });
    when(repository.findByIdForUpdate(any()))
        .thenAnswer(
            invocation -> {
              ProjectChatConfirmation row = stored.get();
              return row != null && row.getId().equals(invocation.getArgument(0))
                  ? Optional.of(row)
                  : Optional.empty();
            });
    when(executor.execute(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              ProjectChatConfirmation consumed = invocation.getArgument(1);
              assertThat(consumed.getStatus()).isEqualTo(ProjectChatConfirmation.Status.CONSUMED);
              return Map.of("status", "COMPLETED");
            });
    var consumer = new ProjectChatConfirmationConsumer(repository, Clock.systemUTC());
    service =
        new ProjectChatConfirmationService(
            repository,
            consumer,
            executor,
            guard,
            providerResolver,
            modelResolver,
            orgModelOverrides,
            audit,
            new ObjectMapper());
  }

  @Test
  void storesOnlyTokenDigestAndReturnsSecretOnce() {
    var issued = service.issue("project-1", principal, docScope("SETUP"));

    assertThat(issued.confirmationToken()).startsWith("es_chat_confirm_");
    assertThat(stored.get().getTokenHash()).hasSize(64).doesNotContain(issued.confirmationToken());
    assertThat(stored.get().getScopeHash()).hasSize(64);
    assertThat(stored.get().getTargetHash()).hasSize(64);
    assertThat(stored.get().getTargetJson()).isEqualTo("{\"docType\":\"SETUP\"}");
    assertThat(stored.get().getCapability()).isEqualTo("SPEND_AI");
    assertThat(issued.confirmation().budget().maxTotalTokens()).isEqualTo(200_000L);
    assertThat(issued.confirmation().budget().maxCostUsd()).isEqualByComparingTo("3");
    assertThat(issued.confirmation().budget().maxProviderCalls()).isEqualTo(2);
    verify(audit)
        .recordConfirmation(
            "CHAT_CONFIRMATION_ISSUED",
            "project-1",
            "org-1",
            principal,
            issued.confirmation().confirmationId().toString(),
            "GENERATE_PROJECT_DOC",
            "deepseek",
            "deepseek-v4",
            stored.get().getTargetHash(),
            "ISSUED");
  }

  @Test
  void targetSubstitutionFailsWithoutExecutingAndOriginalScopeStillWorksOnce() {
    var issued = service.issue("project-1", principal, docScope("SETUP"));

    assertThatThrownBy(
            () ->
                service.consumeAndExecute(
                    "project-1",
                    issued.confirmation().confirmationId(),
                    principal,
                    issued.confirmationToken(),
                    docScope("EXPLANATION")))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode().value()).isEqualTo(403));
    assertThat(stored.get().getStatus()).isEqualTo(ProjectChatConfirmation.Status.PENDING);

    var result =
        service.consumeAndExecute(
            "project-1",
            issued.confirmation().confirmationId(),
            principal,
            issued.confirmationToken(),
            docScope("SETUP"));
    assertThat(result.confirmation().consumed()).isTrue();

    assertThatThrownBy(
            () ->
                service.consumeAndExecute(
                    "project-1",
                    issued.confirmation().confirmationId(),
                    principal,
                    issued.confirmationToken(),
                    docScope("SETUP")))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
    verify(executor, times(1)).execute(any(), any(), any());
  }

  @Test
  void changedProviderModelOrActorCannotConsume() {
    var issued = service.issue("project-1", principal, docScope("SETUP"));

    assertThatThrownBy(
            () ->
                service.consumeAndExecute(
                    "project-1",
                    issued.confirmation().confirmationId(),
                    principal,
                    issued.confirmationToken(),
                    new ProjectChatConfirmationService.ActionScope(
                        "GENERATE_PROJECT_DOC",
                        "openrouter",
                        "deepseek-v4",
                        Map.of("docType", "SETUP"))))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () ->
                service.consumeAndExecute(
                    "project-1",
                    issued.confirmation().confirmationId(),
                    principal,
                    issued.confirmationToken(),
                    new ProjectChatConfirmationService.ActionScope(
                        "GENERATE_PROJECT_DOC",
                        "deepseek",
                        "different-model",
                        Map.of("docType", "SETUP"))))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () ->
                service.consumeAndExecute(
                    "project-1",
                    issued.confirmation().confirmationId(),
                    principal("user-2"),
                    issued.confirmationToken(),
                    docScope("SETUP")))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode().value()).isEqualTo(403));
    verify(executor, times(0)).execute(any(), any(), any());
  }

  @Test
  void issueResolvesUnderOrgOverridesAndRestoresPriorRouteContext() {
    Map<String, String> orgRoute =
        Map.of(RunModelOverrides.key(LlmProvider.DEEPSEEK, AgentTier.WORKHORSE), "deepseek-v4");
    Map<String, String> prior =
        Map.of(RunModelOverrides.key(LlmProvider.ANTHROPIC, AgentTier.PLANNING), "prior-model");
    when(orgModelOverrides.overridesForRun("org-1")).thenReturn(orgRoute);
    when(modelResolver.resolve("ProjectDocGeneratorService", LlmProvider.DEEPSEEK))
        .thenAnswer(
            ignored -> {
              assertThat(RunModelOverrides.lookup(LlmProvider.DEEPSEEK, AgentTier.WORKHORSE))
                  .isEqualTo("deepseek-v4");
              return ResolvedModel.builder()
                  .requestedProvider(LlmProvider.DEEPSEEK)
                  .actualProvider(LlmProvider.DEEPSEEK)
                  .model("deepseek-v4")
                  .agentTier(AgentTier.WORKHORSE)
                  .build();
            });
    RunModelOverrides.bind(prior);

    service.issue("project-1", principal, docScope("SETUP"));

    assertThat(RunModelOverrides.capture()).isEqualTo(prior);
  }

  @Test
  void routeDriftBeforeConsumeRejectsWithoutBurningGrant() {
    var issued = service.issue("project-1", principal, docScope("SETUP"));
    when(modelResolver.resolve("ProjectDocGeneratorService", LlmProvider.DEEPSEEK))
        .thenReturn(
            ResolvedModel.builder()
                .requestedProvider(LlmProvider.DEEPSEEK)
                .actualProvider(LlmProvider.DEEPSEEK)
                .model("deepseek-v4-next")
                .agentTier(AgentTier.WORKHORSE)
                .build());

    assertThatThrownBy(
            () ->
                service.consumeAndExecute(
                    "project-1",
                    issued.confirmation().confirmationId(),
                    principal,
                    issued.confirmationToken(),
                    docScope("SETUP")))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
    assertThat(stored.get().getStatus()).isEqualTo(ProjectChatConfirmation.Status.PENDING);
    verify(executor, times(0)).execute(any(), any(), any());
  }

  @Test
  void wrongTokenAndExpiredGrantNeverExecute() {
    var issued = service.issue("project-1", principal, docScope("SETUP"));

    assertThatThrownBy(
            () ->
                service.consumeAndExecute(
                    "project-1",
                    issued.confirmation().confirmationId(),
                    principal,
                    "es_chat_confirm_wrong",
                    docScope("SETUP")))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode().value()).isEqualTo(403));
    assertThat(stored.get().getStatus()).isEqualTo(ProjectChatConfirmation.Status.PENDING);

    stored.get().setExpiresAt(Instant.now().minusSeconds(1));
    assertThatThrownBy(
            () ->
                service.consumeAndExecute(
                    "project-1",
                    issued.confirmation().confirmationId(),
                    principal,
                    issued.confirmationToken(),
                    docScope("SETUP")))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode().value()).isEqualTo(410));
    assertThat(stored.get().getStatus()).isEqualTo(ProjectChatConfirmation.Status.EXPIRED);
    verify(executor, times(0)).execute(any(), any(), any());
  }

  @Test
  void executorFailureStillBurnsGrant() {
    doThrow(new IllegalStateException("downstream")).when(executor).execute(any(), any(), any());
    var issued = service.issue("project-1", principal, docScope("SETUP"));

    assertThatThrownBy(
            () ->
                service.consumeAndExecute(
                    "project-1",
                    issued.confirmation().confirmationId(),
                    principal,
                    issued.confirmationToken(),
                    docScope("SETUP")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("downstream");
    assertThat(stored.get().getStatus()).isEqualTo(ProjectChatConfirmation.Status.CONSUMED);

    assertThatThrownBy(
            () ->
                service.consumeAndExecute(
                    "project-1",
                    issued.confirmation().confirmationId(),
                    principal,
                    issued.confirmationToken(),
                    docScope("SETUP")))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode().value()).isEqualTo(409));
    verify(executor, times(1)).execute(any(), any(), any());
  }

  private static ProjectChatConfirmationService.ActionScope docScope(String type) {
    return new ProjectChatConfirmationService.ActionScope(
        "GENERATE_PROJECT_DOC", "deepseek", "deepseek-v4", Map.of("docType", type));
  }

  private static AppUserDetails principal(String userId) {
    return new AppUserDetails(
        AppUser.builder()
            .id(userId)
            .orgId("org-1")
            .email(userId + "@example.com")
            .globalRole("USER")
            .tier("ENTERPRISE")
            .status("ACTIVE")
            .build(),
        null);
  }
}
