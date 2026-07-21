package com.navneet.executespec.executioncontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * C1 Step 1 — cross-provider failover must be structurally suppressed for operator-controlled
 * benchmark/SWE-bench workers, while ordinary product runs keep failing over.
 *
 * <p>Cross-provider failover is ENABLED in production (V266). Without this suppression an unpinned
 * SWE-bench task can be served by a different physical provider while every campaign artifact still
 * reports the requested one, which invalidates any cross-provider comparison built on it.
 */
class LlmExecutionPolicyFactoryControlledWorkerTest {

  private final LlmExecutionContextService contexts = mock(LlmExecutionContextService.class);
  private final PipelineRunRepository runs = mock(PipelineRunRepository.class);
  private final OrgRepository orgs = mock(OrgRepository.class);
  private final ApiKeyResolver apiKeyResolver = mock(ApiKeyResolver.class);
  private final ModelRegistry modelRegistry = mock(ModelRegistry.class);

  private LlmExecutionPolicyFactory factory() {
    LlmExecutionPolicyFactory factory =
        new LlmExecutionPolicyFactory(
            contexts,
            runs,
            mock(AgentSessionRepository.class),
            mock(ProjectRepository.class),
            orgs,
            apiKeyResolver,
            modelRegistry);
    // Platform keys for two providers so a failover chain is genuinely available on paper.
    ReflectionTestUtils.setField(factory, "platformDeepSeekKey", "ds-key");
    ReflectionTestUtils.setField(factory, "platformOpenRouterKey", "or-key");
    return factory;
  }

  private PipelineRun run(String sourceType) {
    PipelineRun run = new PipelineRun();
    run.setRunId("run-1");
    run.setCreatedByUserId("user-1");
    run.setOrgId("org-1");
    run.setAiProvider("deepseek");
    run.setSourceType(sourceType);
    when(runs.findById("run-1")).thenReturn(Optional.of(run));
    return run;
  }

  private void org(String keySourceMode) {
    when(orgs.findById("org-1"))
        .thenReturn(
            Optional.of(
                Org.builder()
                    .id("org-1")
                    .tier("PRO")
                    .keySourceMode(keySourceMode)
                    .enabledProviders("deepseek,openrouter")
                    .build()));
    when(apiKeyResolver.resolveWithProvenance("user-1")).thenReturn(credentials(keySourceMode));
  }

  private ApiKeyResolver.CredentialResolution credentials(String keySourceMode) {
    Map<String, ApiKeyResolver.CredentialOrigin> origins =
        "OWN_KEYS".equals(keySourceMode)
            ? Map.of(
                "deepseek",
                ApiKeyResolver.CredentialOrigin.ORG,
                "openrouter",
                ApiKeyResolver.CredentialOrigin.ORG)
            : Map.of();
    return new ApiKeyResolver.CredentialResolution(null, "org-1", origins);
  }

  private LlmPolicySnapshot capturePolicy() {
    ArgumentCaptor<LlmExecutionContextCreateRequest> captor =
        ArgumentCaptor.forClass(LlmExecutionContextCreateRequest.class);
    verify(contexts).create(captor.capture());
    return captor.getValue().policy();
  }

  private String captureIdempotencyKey() {
    ArgumentCaptor<LlmExecutionContextCreateRequest> captor =
        ArgumentCaptor.forClass(LlmExecutionContextCreateRequest.class);
    verify(contexts).create(captor.capture());
    return captor.getValue().idempotencyKey();
  }

  private void stubContextCreation() {
    LlmExecutionContext created = mock(LlmExecutionContext.class);
    when(created.getId()).thenReturn("llmctx-new");
    when(created.scope()).thenReturn(LlmExecutionScope.customer("org-1"));
    when(contexts.create(any())).thenReturn(created);
  }

  @Test
  void benchmarkSourcedRunSuppressesCrossProviderFailover() {
    run("benchmark");
    org("HYBRID");
    stubContextCreation();

    factory().forRun("run-1");

    assertThat(capturePolicy().allowProviderFailover()).isFalse();
  }

  @Test
  void swebenchSourcedRunSuppressesCrossProviderFailover() {
    run("swebench-lite");
    org("HYBRID");
    stubContextCreation();

    factory().forRun("run-1");

    assertThat(capturePolicy().allowProviderFailover()).isFalse();
  }

  @Test
  void normalProductRunStillAllowsFailover() {
    run("web");
    org("HYBRID");
    stubContextCreation();

    factory().forRun("run-1");

    assertThat(capturePolicy().allowProviderFailover()).isTrue();
  }

  @Test
  void controlledWorkerUsesADistinctRoutePolicyGenerationSoCachedContextsCannotServeStalePolicy() {
    run("benchmark");
    org("HYBRID");
    stubContextCreation();

    factory().forRun("run-1");

    assertThat(captureIdempotencyKey())
        .isEqualTo(LlmExecutionPolicyFactory.CONTROLLED_WORKER_ROUTE_POLICY_KEY)
        .isNotEqualTo("route-v1");
  }

  @Test
  void resumedBenchmarkRunDoesNotReuseAPermissiveRouteV1Context() {
    run("benchmark");
    org("HYBRID");
    stubContextCreation();
    LlmExecutionScope scope = LlmExecutionScope.customer("org-1");
    // A context from BEFORE this change still exists under the old key. It must not be served.
    when(contexts.findReference(scope, "pipeline", "PIPELINE_RUN", "run-1", "route-v1"))
        .thenReturn(Optional.of(new LlmExecutionReference("llmctx-stale-permissive", scope)));

    LlmExecutionReference reference = factory().forRun("run-1");

    assertThat(reference.contextId()).isNotEqualTo("llmctx-stale-permissive");
    assertThat(capturePolicy().allowProviderFailover()).isFalse();
  }

  @Test
  void resumedBenchmarkRunReusesItsOwnControlledWorkerContext() {
    run("benchmark");
    org("HYBRID");
    LlmExecutionScope scope = LlmExecutionScope.customer("org-1");
    when(contexts.findReference(
            scope,
            "pipeline",
            "PIPELINE_RUN",
            "run-1",
            LlmExecutionPolicyFactory.CONTROLLED_WORKER_ROUTE_POLICY_KEY))
        .thenReturn(Optional.of(new LlmExecutionReference("llmctx-controlled", scope)));

    assertThat(factory().forRun("run-1").contextId()).isEqualTo("llmctx-controlled");
    verify(contexts, never()).create(any());
  }

  @Test
  void ownKeysLockRemainsIndependentOfTheControlledWorkerTerm() {
    // BYOK containment must not depend on the new term: an OWN_KEYS product run keeps
    // allowProviderFailover=false and keeps its own route-v2 policy generation.
    run("web");
    org("OWN_KEYS");
    stubContextCreation();

    factory().forRun("run-1");

    assertThat(capturePolicy().allowProviderFailover()).isFalse();
    assertThat(captureIdempotencyKey()).isEqualTo("route-v2-own-keys-provider-lock");
  }

  @Test
  void ownKeysBenchmarkRunKeepsTheByokPolicyGenerationAndStaysLocked() {
    run("benchmark");
    org("OWN_KEYS");
    stubContextCreation();

    factory().forRun("run-1");

    assertThat(capturePolicy().allowProviderFailover()).isFalse();
    // BYOK is the LEADING term, so it still owns the policy generation for a benchmark run.
    assertThat(captureIdempotencyKey()).isEqualTo("route-v2-own-keys-provider-lock");
  }

  @Test
  void controlledWorkerDiscriminatorMatchesTheCodeGenEngineSourceTypePrefixes() {
    assertThat(LlmExecutionPolicyFactory.isControlledWorkerRun("benchmark")).isTrue();
    assertThat(LlmExecutionPolicyFactory.isControlledWorkerRun("benchmark-pro")).isTrue();
    assertThat(LlmExecutionPolicyFactory.isControlledWorkerRun("swebench")).isTrue();
    assertThat(LlmExecutionPolicyFactory.isControlledWorkerRun("SWEBENCH-LITE")).isTrue();
    assertThat(LlmExecutionPolicyFactory.isControlledWorkerRun("web")).isFalse();
    assertThat(LlmExecutionPolicyFactory.isControlledWorkerRun("finding:CI_FAILURE")).isFalse();
    assertThat(LlmExecutionPolicyFactory.isControlledWorkerRun(null)).isFalse();
    assertThat(LlmExecutionPolicyFactory.isControlledWorkerRun("  ")).isFalse();
  }
}
