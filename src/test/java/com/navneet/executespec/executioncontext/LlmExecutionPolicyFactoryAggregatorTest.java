package com.navneet.executespec.executioncontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agent.ResolvedKeys;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelMatrixProperties;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.specchat.ChatToolPolicy;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class LlmExecutionPolicyFactoryAggregatorTest {

  @Test
  void runRecoveryReusesImmutableContextAfterDerivedBudgetChanges() {
    LlmExecutionContextService contexts = mock(LlmExecutionContextService.class);
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    PipelineRun run = new PipelineRun();
    run.setRunId("run-1");
    run.setCreatedByUserId("user-1");
    run.setOrgId("org-1");
    run.setHardCapUsd(7.25);
    LlmExecutionScope scope = LlmExecutionScope.customer("org-1");
    LlmExecutionReference original = new LlmExecutionReference("llmctx-original", scope);
    OrgRepository orgs = mock(OrgRepository.class);
    ApiKeyResolver keys = mock(ApiKeyResolver.class);
    when(runs.findById("run-1")).thenReturn(java.util.Optional.of(run));
    when(orgs.findById("org-1"))
        .thenReturn(
            java.util.Optional.of(
                Org.builder().id("org-1").tier("PRO").keySourceMode("HYBRID").build()));
    when(keys.resolveWithProvenance("user-1"))
        .thenReturn(
            new ApiKeyResolver.CredentialResolution(
                new ResolvedKeys(null, null, null, null, null, null, "HYBRID", Set.of("deepseek")),
                "org-1",
                Map.of()));
    when(contexts.findReference(scope, "pipeline", "PIPELINE_RUN", "run-1", "route-v1"))
        .thenReturn(java.util.Optional.of(original));
    LlmExecutionPolicyFactory factory =
        new LlmExecutionPolicyFactory(
            contexts,
            runs,
            mock(AgentSessionRepository.class),
            mock(ProjectRepository.class),
            orgs,
            keys,
            mock(ModelRegistry.class));

    assertThat(factory.forRun("run-1")).isEqualTo(original);
    verify(contexts, never()).create(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void runRecoveryPrefersExistingOwnKeysContextAfterOrgChangesToHybrid() {
    LlmExecutionContextService contexts = mock(LlmExecutionContextService.class);
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    PipelineRun run = new PipelineRun();
    run.setRunId("run-1");
    run.setCreatedByUserId("user-1");
    run.setOrgId("org-1");
    LlmExecutionScope scope = LlmExecutionScope.customer("org-1");
    LlmExecutionReference ownKeys = new LlmExecutionReference("llmctx-own-keys", scope);
    when(runs.findById("run-1")).thenReturn(java.util.Optional.of(run));
    when(contexts.findReference(
            scope, "pipeline", "PIPELINE_RUN", "run-1", "route-v2-own-keys-provider-lock"))
        .thenReturn(java.util.Optional.of(ownKeys));
    OrgRepository orgs = mock(OrgRepository.class);
    LlmExecutionPolicyFactory factory =
        new LlmExecutionPolicyFactory(
            contexts,
            runs,
            mock(AgentSessionRepository.class),
            mock(ProjectRepository.class),
            orgs,
            mock(ApiKeyResolver.class),
            mock(ModelRegistry.class));

    assertThat(factory.forRun("run-1")).isEqualTo(ownKeys);
    verify(orgs, never()).findById("org-1");
    verify(contexts, never()).findReference(scope, "pipeline", "PIPELINE_RUN", "run-1", "route-v1");
    verify(contexts, never()).create(any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void openRouterAllowListIncludesSupportedModelsOwnedByNativeProviders() {
    ModelRegistry registry = mock(ModelRegistry.class);
    ModelMatrixProperties.ModelConfig claude = new ModelMatrixProperties.ModelConfig();
    claude.setProvider("anthropic");
    when(registry.allModels()).thenReturn(Map.of("claude-sonnet-4-6", claude));
    LlmExecutionPolicyFactory factory =
        new LlmExecutionPolicyFactory(
            mock(LlmExecutionContextService.class),
            mock(PipelineRunRepository.class),
            mock(AgentSessionRepository.class),
            mock(ProjectRepository.class),
            mock(OrgRepository.class),
            mock(ApiKeyResolver.class),
            registry);

    Map<String, List<String>> models =
        ReflectionTestUtils.invokeMethod(factory, "modelsFor", Set.of("anthropic", "openrouter"));

    assertThat(models.get("anthropic")).containsExactly("claude-sonnet-4-6");
    assertThat(models.get("openrouter")).containsExactly("claude-sonnet-4-6");
  }

  @Test
  void ownKeysRunDoesNotRecoverLegacyMultiProviderPolicy() {
    LlmExecutionContextService contexts = mock(LlmExecutionContextService.class);
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    OrgRepository orgs = mock(OrgRepository.class);
    ApiKeyResolver keys = mock(ApiKeyResolver.class);
    ModelRegistry registry = mock(ModelRegistry.class);
    PipelineRun run = new PipelineRun();
    run.setRunId("run-1");
    run.setCreatedByUserId("user-1");
    run.setOrgId("org-1");
    run.setAiProvider("DEEPSEEK");
    LlmExecutionScope scope = LlmExecutionScope.customer("org-1");
    when(runs.findById("run-1")).thenReturn(java.util.Optional.of(run));
    when(orgs.findById("org-1"))
        .thenReturn(
            java.util.Optional.of(
                Org.builder().id("org-1").tier("ENTERPRISE").keySourceMode("OWN_KEYS").build()));
    when(contexts.findReference(
            scope, "pipeline", "PIPELINE_RUN", "run-1", "route-v2-own-keys-provider-lock"))
        .thenReturn(java.util.Optional.empty());
    when(keys.resolveWithProvenance("user-1"))
        .thenReturn(
            new ApiKeyResolver.CredentialResolution(
                new ResolvedKeys(
                    null,
                    null,
                    null,
                    "openrouter-secret",
                    "deepseek-secret",
                    null,
                    "OWN_KEYS",
                    Set.of("openrouter", "deepseek")),
                "org-1",
                Map.of(
                    "openrouter", ApiKeyResolver.CredentialOrigin.ORG,
                    "deepseek", ApiKeyResolver.CredentialOrigin.ORG)));
    when(registry.allModels()).thenReturn(Map.of());
    LlmExecutionContext saved = mock(LlmExecutionContext.class);
    when(saved.getId()).thenReturn("context-v2");
    when(contexts.create(any())).thenReturn(saved);
    LlmExecutionPolicyFactory factory =
        new LlmExecutionPolicyFactory(
            contexts,
            runs,
            mock(AgentSessionRepository.class),
            mock(ProjectRepository.class),
            orgs,
            keys,
            registry);

    assertThat(factory.forRun("run-1").contextId()).isEqualTo("context-v2");

    ArgumentCaptor<LlmExecutionContextCreateRequest> request =
        ArgumentCaptor.forClass(LlmExecutionContextCreateRequest.class);
    verify(contexts).create(request.capture());
    assertThat(request.getValue().idempotencyKey()).isEqualTo("route-v2-own-keys-provider-lock");
    assertThat(request.getValue().policy().allowedProviders()).containsExactly("deepseek");
    verify(contexts, never()).findReference(scope, "pipeline", "PIPELINE_RUN", "run-1", "route-v1");
  }

  @Test
  void previewSurfaceGetsDedicatedBoundedContextWithRunCredentialPolicy() {
    LlmExecutionContextService contexts = mock(LlmExecutionContextService.class);
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    OrgRepository orgs = mock(OrgRepository.class);
    ApiKeyResolver keys = mock(ApiKeyResolver.class);
    ModelRegistry registry = mock(ModelRegistry.class);
    PipelineRun run = new PipelineRun();
    run.setRunId("run-1");
    run.setCreatedByUserId("user-1");
    run.setOrgId("org-1");
    run.setAiProvider("DEEPSEEK");
    when(runs.findById("run-1")).thenReturn(java.util.Optional.of(run));
    when(orgs.findById("org-1"))
        .thenReturn(
            java.util.Optional.of(
                Org.builder().id("org-1").tier("BYOK").keySourceMode("OWN_KEYS").build()));
    when(keys.resolveWithProvenance("user-1"))
        .thenReturn(
            new ApiKeyResolver.CredentialResolution(
                new ResolvedKeys(
                    null,
                    null,
                    null,
                    null,
                    "deepseek-secret",
                    null,
                    "OWN_KEYS",
                    Set.of("deepseek")),
                "org-1",
                Map.of("deepseek", ApiKeyResolver.CredentialOrigin.ORG)));
    when(registry.allModels()).thenReturn(Map.of());
    LlmExecutionContext saved = mock(LlmExecutionContext.class);
    when(saved.getId()).thenReturn("preview-context");
    when(contexts.create(any())).thenReturn(saved);
    LlmExecutionPolicyFactory factory =
        new LlmExecutionPolicyFactory(
            contexts,
            runs,
            mock(AgentSessionRepository.class),
            mock(ProjectRepository.class),
            orgs,
            keys,
            registry);

    factory.forRunPreviewSurface("run-1", "job-1:home");

    ArgumentCaptor<LlmExecutionContextCreateRequest> request =
        ArgumentCaptor.forClass(LlmExecutionContextCreateRequest.class);
    verify(contexts).create(request.capture());
    LlmExecutionContextCreateRequest created = request.getValue();
    assertThat(created.feature()).isEqualTo("preview-render");
    assertThat(created.sourceType()).isEqualTo("PREVIEW_RENDER");
    assertThat(created.sourceId()).isEqualTo("preview-render:run-1:job-1:home");
    assertThat(created.budget()).isEqualTo(LlmExecutionPolicyFactory.PREVIEW_SURFACE_BUDGET);
    assertThat(created.budget().maxProviderCalls()).isEqualTo(4);
    assertThat(created.budget().maxOutputTokens()).isEqualTo(8_192L);
    assertThat(created.policy().keySourceMode())
        .isEqualTo(LlmPolicySnapshot.KeySourceMode.OWN_KEYS);
    assertThat(created.policy().allowedProviders()).containsExactly("deepseek");
  }

  @Test
  void runChatTurnGetsDedicatedBoundedContextWithRunCredentialPolicy() {
    LlmExecutionContextService contexts = mock(LlmExecutionContextService.class);
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    OrgRepository orgs = mock(OrgRepository.class);
    ApiKeyResolver keys = mock(ApiKeyResolver.class);
    ModelRegistry registry = mock(ModelRegistry.class);
    PipelineRun run = new PipelineRun();
    run.setRunId("run-1");
    run.setCreatedByUserId("user-1");
    run.setOrgId("org-1");
    run.setAiProvider("DEEPSEEK");
    when(runs.findById("run-1")).thenReturn(java.util.Optional.of(run));
    when(orgs.findById("org-1"))
        .thenReturn(
            java.util.Optional.of(
                Org.builder().id("org-1").tier("BYOK").keySourceMode("OWN_KEYS").build()));
    when(keys.resolveWithProvenance("user-1"))
        .thenReturn(
            new ApiKeyResolver.CredentialResolution(
                new ResolvedKeys(
                    null,
                    null,
                    null,
                    null,
                    "deepseek-secret",
                    null,
                    "OWN_KEYS",
                    Set.of("deepseek")),
                "org-1",
                Map.of("deepseek", ApiKeyResolver.CredentialOrigin.ORG)));
    when(registry.allModels()).thenReturn(Map.of());
    LlmExecutionContext saved = mock(LlmExecutionContext.class);
    when(saved.getId()).thenReturn("chat-context");
    when(contexts.create(any())).thenReturn(saved);
    LlmExecutionPolicyFactory factory =
        new LlmExecutionPolicyFactory(
            contexts,
            runs,
            mock(AgentSessionRepository.class),
            mock(ProjectRepository.class),
            orgs,
            keys,
            registry);

    factory.forRunChatTurn("run-1", "turn-1");

    ArgumentCaptor<LlmExecutionContextCreateRequest> request =
        ArgumentCaptor.forClass(LlmExecutionContextCreateRequest.class);
    verify(contexts).create(request.capture());
    LlmExecutionContextCreateRequest created = request.getValue();
    assertThat(created.feature()).isEqualTo("run-chat");
    assertThat(created.sourceType()).isEqualTo("RUN_CHAT");
    assertThat(created.sourceId()).isEqualTo("run-chat:run-1:turn-1");
    assertThat(created.budget()).isEqualTo(LlmExecutionPolicyFactory.RUN_CHAT_TURN_BUDGET);
    assertThat(created.budget().maxProviderCalls()).isEqualTo(20);
    assertThat(created.budget().maxOutputTokens()).isEqualTo(15_000L);
    assertThat(created.budget().maxTotalTokens()).isEqualTo(415_000L);
    assertThat(created.budget().maxCostUsd()).isEqualByComparingTo("2.00");
    assertThat(created.policy().keySourceMode())
        .isEqualTo(LlmPolicySnapshot.KeySourceMode.OWN_KEYS);
    assertThat(created.policy().allowedProviders()).containsExactly("deepseek");
  }

  @Test
  void externalCollaboratorRunAskAndPreviewKeepHostTenantButUseDurablePayerPolicy() {
    LlmExecutionContextService contexts = mock(LlmExecutionContextService.class);
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    OrgRepository orgs = mock(OrgRepository.class);
    ApiKeyResolver keys = mock(ApiKeyResolver.class);
    ModelRegistry registry = mock(ModelRegistry.class);
    PipelineRun run = new PipelineRun();
    run.setRunId("external-run");
    run.setCreatedByUserId("external-user");
    run.setOrgId("host-org");
    run.setBillingOrgId("payer-org");
    run.setProjectId("host-project");
    run.setAiProvider("DEEPSEEK");
    when(runs.findById("external-run")).thenReturn(java.util.Optional.of(run));
    when(orgs.findById("payer-org"))
        .thenReturn(
            java.util.Optional.of(
                Org.builder().id("payer-org").tier("BYOK").keySourceMode("OWN_KEYS").build()));
    when(keys.resolveWithProvenance("external-user"))
        .thenReturn(
            new ApiKeyResolver.CredentialResolution(
                new ResolvedKeys(
                    null,
                    null,
                    null,
                    null,
                    "payer-deepseek-secret",
                    null,
                    "OWN_KEYS",
                    Set.of("deepseek")),
                "payer-org",
                Map.of("deepseek", ApiKeyResolver.CredentialOrigin.USER)));
    when(registry.allModels()).thenReturn(Map.of());
    LlmExecutionContext saved = mock(LlmExecutionContext.class);
    when(saved.getId()).thenReturn("external-context");
    when(contexts.create(any())).thenReturn(saved);
    LlmExecutionPolicyFactory factory =
        new LlmExecutionPolicyFactory(
            contexts,
            runs,
            mock(AgentSessionRepository.class),
            mock(ProjectRepository.class),
            orgs,
            keys,
            registry);

    factory.forRun("external-run");
    factory.forRunChatTurn("external-run", "ask-turn");
    factory.forRunPreviewSurface("external-run", "screen-attempt");

    ArgumentCaptor<LlmExecutionContextCreateRequest> requests =
        ArgumentCaptor.forClass(LlmExecutionContextCreateRequest.class);
    verify(contexts, times(3)).create(requests.capture());
    assertThat(requests.getAllValues())
        .extracting(request -> request.scope().orgId())
        .containsOnly("host-org");
    assertThat(requests.getAllValues())
        .extracting(LlmExecutionContextCreateRequest::credentialOrgId)
        .containsOnly("payer-org");
    assertThat(requests.getAllValues())
        .extracting(LlmExecutionContextCreateRequest::userId)
        .containsOnly("external-user");
    assertThat(requests.getAllValues())
        .extracting(request -> request.policy().keySubjectUserId())
        .containsOnly("external-user");
    assertThat(requests.getAllValues())
        .extracting(LlmExecutionContextCreateRequest::feature)
        .containsExactly("pipeline", "run-chat", "preview-render");
  }

  @Test
  void webAgentTurnsCreateDistinctDurableBudgetContexts() {
    LlmExecutionContextService contexts = mock(LlmExecutionContextService.class);
    OrgRepository orgs = mock(OrgRepository.class);
    ApiKeyResolver keys = mock(ApiKeyResolver.class);
    ModelRegistry registry = mock(ModelRegistry.class);
    when(orgs.findById("org-1"))
        .thenReturn(
            java.util.Optional.of(
                Org.builder().id("org-1").tier("PRO").keySourceMode("OWN_KEYS").build()));
    when(keys.resolveWithProvenance("user-1"))
        .thenReturn(
            new ApiKeyResolver.CredentialResolution(
                new ResolvedKeys(
                    null,
                    null,
                    null,
                    null,
                    "deepseek-secret",
                    null,
                    "OWN_KEYS",
                    Set.of("deepseek")),
                "org-1",
                Map.of("deepseek", ApiKeyResolver.CredentialOrigin.USER)));
    when(registry.allModels()).thenReturn(Map.of());
    LlmExecutionContext first = mock(LlmExecutionContext.class);
    LlmExecutionContext second = mock(LlmExecutionContext.class);
    when(first.getId()).thenReturn("chat-turn-context-1");
    when(second.getId()).thenReturn("chat-turn-context-2");
    when(contexts.create(any())).thenReturn(first, second);
    LlmExecutionPolicyFactory factory =
        new LlmExecutionPolicyFactory(
            contexts,
            mock(PipelineRunRepository.class),
            mock(AgentSessionRepository.class),
            mock(ProjectRepository.class),
            orgs,
            keys,
            registry);
    LlmExecutionBudget budget =
        new LlmExecutionBudget(
            null,
            null,
            40_000L,
            new java.math.BigDecimal("0.50"),
            ChatToolPolicy.MAX_PROVIDER_CALLS_PER_TURN);

    LlmExecutionReference firstReference =
        factory.forChatTurn(
            "user-1",
            "org-1",
            null,
            "spec-chat",
            "session-1",
            "turn-1",
            "correlation-1",
            "deepseek",
            null,
            budget);
    LlmExecutionReference secondReference =
        factory.forChatTurn(
            "user-1",
            "org-1",
            null,
            "spec-chat",
            "session-1",
            "turn-2",
            "correlation-2",
            "deepseek",
            null,
            budget);

    assertThat(firstReference.contextId()).isNotEqualTo(secondReference.contextId());
    ArgumentCaptor<LlmExecutionContextCreateRequest> requests =
        ArgumentCaptor.forClass(LlmExecutionContextCreateRequest.class);
    verify(contexts, times(2)).create(requests.capture());
    assertThat(requests.getAllValues())
        .extracting(LlmExecutionContextCreateRequest::sourceId)
        .doesNotHaveDuplicates();
    assertThat(requests.getAllValues())
        .extracting(LlmExecutionContextCreateRequest::correlationId)
        .containsExactly("correlation-1", "correlation-2");
    assertThat(requests.getAllValues())
        .extracting(LlmExecutionContextCreateRequest::budget)
        .containsOnly(budget);
  }

  @Test
  void enterpriseOwnKeysProjectLocksPolicyToSelectedProvider() {
    CapturedProjectPolicy captured = captureProjectPolicy("ENTERPRISE");

    assertSelectedProviderLock(captured.policy());
  }

  @Test
  void teamOwnKeysProjectLocksPolicyToSelectedProvider() {
    CapturedProjectPolicy captured = captureProjectPolicy("TEAM");

    assertSelectedProviderLock(captured.policy());
  }

  @Test
  void byokOwnKeysProjectLocksPolicyToSelectedProvider() {
    CapturedProjectPolicy captured = captureProjectPolicy("BYOK");

    assertSelectedProviderLock(captured.policy());
  }

  @Test
  void projectEmbeddingIdempotencyNamespaceIncludesInitiatingActor() {
    LlmExecutionContextService contexts = mock(LlmExecutionContextService.class);
    ProjectRepository projects = mock(ProjectRepository.class);
    OrgRepository orgs = mock(OrgRepository.class);
    ApiKeyResolver keys = mock(ApiKeyResolver.class);
    ModelRegistry registry = mock(ModelRegistry.class);
    Project project =
        Project.builder()
            .id("project-1")
            .orgId("org-1")
            .createdBy("owner-1")
            .aiProvider(LlmProvider.ANTHROPIC)
            .build();
    Org org =
        Org.builder()
            .id("org-1")
            .tier("ENTERPRISE")
            .keySourceMode("OWN_KEYS")
            .enabledProviders("openai")
            .build();
    when(projects.findById("project-1")).thenReturn(java.util.Optional.of(project));
    when(orgs.findById("org-1")).thenReturn(java.util.Optional.of(org));
    when(keys.resolveWithProvenance("owner-1"))
        .thenReturn(
            new ApiKeyResolver.CredentialResolution(
                new ResolvedKeys(
                    null, "openai-secret", null, null, null, null, "OWN_KEYS", Set.of("openai")),
                "org-1",
                Map.of("openai", ApiKeyResolver.CredentialOrigin.ORG)));
    when(registry.allModels()).thenReturn(Map.of());
    LlmExecutionContext saved = mock(LlmExecutionContext.class);
    when(saved.getId()).thenReturn("context-1");
    when(contexts.create(any())).thenReturn(saved);
    LlmExecutionPolicyFactory factory =
        new LlmExecutionPolicyFactory(
            contexts,
            mock(PipelineRunRepository.class),
            mock(AgentSessionRepository.class),
            projects,
            orgs,
            keys,
            registry);

    factory.forProjectEmbedding(
        "project-1",
        "ProjectCodeEmbedding",
        "PROJECT_REPOSITORY",
        "repo:sha",
        "repo:sha",
        "openai",
        "member-a");
    factory.forProjectEmbedding(
        "project-1",
        "ProjectCodeEmbedding",
        "PROJECT_REPOSITORY",
        "repo:sha",
        "repo:sha",
        "openai",
        "member-b");
    factory.forProjectEmbedding(
        "project-1",
        "ProjectCodeEmbedding",
        "PROJECT_REPOSITORY",
        "repo:sha",
        "repo:sha",
        "openai",
        "member-a");

    ArgumentCaptor<LlmExecutionContextCreateRequest> request =
        ArgumentCaptor.forClass(LlmExecutionContextCreateRequest.class);
    verify(contexts, times(3)).create(request.capture());
    List<LlmExecutionContextCreateRequest> requests = request.getAllValues();
    assertThat(requests.get(0).sourceId()).isEqualTo(requests.get(1).sourceId());
    assertThat(requests.get(0).idempotencyKey()).isNotEqualTo(requests.get(1).idempotencyKey());
    assertThat(requests.get(0).idempotencyKey()).isEqualTo(requests.get(2).idempotencyKey());
    assertThat(requests.get(0).idempotencyKey())
        .startsWith("embedding-route-v2:openai:")
        .doesNotContain("member-a");
  }

  private static void assertSelectedProviderLock(LlmPolicySnapshot policy) {
    assertThat(policy.keySourceMode()).isEqualTo(LlmPolicySnapshot.KeySourceMode.OWN_KEYS);
    assertThat(policy.requestedProvider()).isEqualTo("deepseek");
    assertThat(policy.allowedProviders()).containsExactly("deepseek");
    assertThat(policy.providerKeySources())
        .containsExactlyEntriesOf(Map.of("deepseek", LlmPolicySnapshot.CredentialSource.ORG));
    assertThat(policy.allowedProviderModels()).containsOnlyKeys("deepseek");
    assertThat(policy.allowProviderFailover()).isFalse();
    assertThat(policy.routeSnapshotVersion()).isEqualTo(2);
  }

  private CapturedProjectPolicy captureProjectPolicy(String tier) {
    LlmExecutionContextService contexts = mock(LlmExecutionContextService.class);
    ProjectRepository projects = mock(ProjectRepository.class);
    OrgRepository orgs = mock(OrgRepository.class);
    ApiKeyResolver keys = mock(ApiKeyResolver.class);
    ModelRegistry registry = mock(ModelRegistry.class);
    Project project =
        Project.builder()
            .id("project-1")
            .orgId("org-1")
            .createdBy("owner-1")
            .aiProvider(LlmProvider.DEEPSEEK)
            .build();
    Org org =
        Org.builder()
            .id("org-1")
            .tier(tier)
            .keySourceMode("OWN_KEYS")
            .enabledProviders("openrouter,deepseek")
            .build();
    ResolvedKeys resolvedKeys =
        new ResolvedKeys(
            null,
            null,
            null,
            "openrouter-secret",
            "deepseek-secret",
            null,
            "OWN_KEYS",
            Set.of("openrouter", "deepseek"));
    when(projects.findById("project-1")).thenReturn(java.util.Optional.of(project));
    when(orgs.findById("org-1")).thenReturn(java.util.Optional.of(org));
    when(keys.resolveWithProvenance("owner-1"))
        .thenReturn(
            new ApiKeyResolver.CredentialResolution(
                resolvedKeys,
                "org-1",
                Map.of(
                    "openrouter", ApiKeyResolver.CredentialOrigin.ORG,
                    "deepseek", ApiKeyResolver.CredentialOrigin.ORG)));
    when(registry.allModels()).thenReturn(Map.of());
    LlmExecutionContext saved = mock(LlmExecutionContext.class);
    when(saved.getId()).thenReturn("context-1");
    when(contexts.create(any())).thenReturn(saved);
    LlmExecutionPolicyFactory factory =
        new LlmExecutionPolicyFactory(
            contexts,
            mock(PipelineRunRepository.class),
            mock(AgentSessionRepository.class),
            projects,
            orgs,
            keys,
            registry);

    factory.forProject(
        "project-1", "architecture", "PROJECT", "source-1", "correlation-1", null, null);

    ArgumentCaptor<LlmExecutionContextCreateRequest> request =
        ArgumentCaptor.forClass(LlmExecutionContextCreateRequest.class);
    verify(contexts).create(request.capture());
    return new CapturedProjectPolicy(request.getValue().policy());
  }

  private record CapturedProjectPolicy(LlmPolicySnapshot policy) {}
}
