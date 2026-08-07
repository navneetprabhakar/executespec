package com.navneet.executespec.executioncontext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelAccessService;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.state.BenchmarkSourceType;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Creates the immutable, secret-free routing snapshot used by customer LLM work. The factory is
 * deliberately the only place that converts mutable org/user configuration into a V282 policy.
 */
@Service
@RequiredArgsConstructor
public class LlmExecutionPolicyFactory {

  /**
   * Distinct route-policy generation for controlled benchmark/SWE-bench workers. It MUST differ
   * from {@code route-v1}: durable contexts are recovered by this key, so reusing the old one would
   * let in-flight and resumed benchmark runs keep serving the previous permissive policy and the
   * failover suppression would silently do nothing.
   */
  static final String CONTROLLED_WORKER_ROUTE_POLICY_KEY = "route-v3-controlled-worker";

  /**
   * One screen is one bounded logical render. Four physical calls allow the router's existing
   * same-provider transient retry envelope, while the token/cost ceilings prevent a decorative
   * surface from becoming an ungoverned post-run spend lane.
   */
  static final LlmExecutionBudget PREVIEW_SURFACE_BUDGET =
      new LlmExecutionBudget(100_000L, 8_192L, 110_000L, new BigDecimal("1.00"), 4);

  /**
   * One Ask turn can make four 3,000-token tool-loop calls plus one 3,000-token forced synthesis.
   * The 15,000-token output ceiling therefore admits every logical call even when a reasoning model
   * consumes each call's full allowance; keeping the former 8,000 ceiling caused the durable budget
   * gate to reject the third call or final synthesis. Twenty physical attempts cover four bounded
   * same-provider attempts for each of those five logical calls. The input ceiling accounts for the
   * bounded transcript/evidence plus accumulated read-only tool results; the cost ceiling prevents
   * BYOK turns from becoming an unbounded lane while managed-credit admission remains independently
   * enforced by the router.
   */
  static final LlmExecutionBudget RUN_CHAT_TURN_BUDGET =
      new LlmExecutionBudget(400_000L, 15_000L, 415_000L, new BigDecimal("2.00"), 20);

  private static final List<String> PROVIDERS =
      List.of("anthropic", "openai", "gemini", "openrouter", "deepseek", "fireworks");

  private final LlmExecutionContextService contextService;
  private final PipelineRunRepository runRepository;
  private final AgentSessionRepository agentSessionRepository;
  private final ProjectRepository projectRepository;
  private final OrgRepository orgRepository;
  private final ApiKeyResolver apiKeyResolver;
  private final ModelRegistry modelRegistry;

  @Value("${spring.ai.anthropic.api-key:}")
  private String platformAnthropicKey;

  @Value("${spring.ai.openai.api-key:}")
  private String platformOpenAiKey;

  @Value("${spring.ai.google.genai.api-key:}")
  private String platformGeminiKey;

  @Value("${spring.ai.openrouter.api-key:}")
  private String platformOpenRouterKey;

  @Value("${spring.ai.deepseek.api-key:}")
  private String platformDeepSeekKey;

  @Value("${spring.ai.fireworks.api-key:}")
  private String platformFireworksKey;

  public LlmExecutionReference forRun(String runId) {
    var run =
        runRepository
            .findById(require(runId, "runId"))
            .orElseThrow(() -> new IllegalArgumentException("pipeline run not found"));
    String userId = require(run.getCreatedByUserId(), "run owner");
    String orgId = require(run.getOrgId(), "run org");
    LlmExecutionScope scope = LlmExecutionScope.customer(orgId);
    java.util.Optional<LlmExecutionReference> ownKeysContext =
        contextService.findReference(
            scope, "pipeline", "PIPELINE_RUN", runId, "route-v2-own-keys-provider-lock");
    if (ownKeysContext.isPresent()) {
      return ownKeysContext.get();
    }
    ApiKeyResolver.CredentialResolution runCredentials =
        apiKeyResolver.resolveWithProvenance(userId);
    String credentialOrgId = require(runCredentials.orgId(), "credential org");
    String durableBillingOrgId = firstNonBlank(run.getBillingOrgId(), orgId);
    if (!durableBillingOrgId.equals(credentialOrgId)) {
      throw new IllegalStateException(
          "Run credential subject no longer belongs to the durable billing org");
    }
    Org org =
        orgRepository
            .findById(credentialOrgId)
            .orElseThrow(() -> new IllegalArgumentException("credential policy org not found"));
    boolean ownKeysProviderLock = isOwnKeys(keySourceMode(org.getKeySourceMode()));
    boolean controlledWorker = isControlledWorkerRun(run.getSourceType());
    String routePolicyKey =
        ownKeysProviderLock
            ? "route-v2-own-keys-provider-lock"
            : controlledWorker ? CONTROLLED_WORKER_ROUTE_POLICY_KEY : "route-v1";
    java.util.Optional<LlmExecutionReference> existing =
        ownKeysProviderLock
            ? java.util.Optional.empty()
            : contextService.findReference(
                scope, "pipeline", "PIPELINE_RUN", runId, routePolicyKey);
    if (existing.isPresent()) {
      return existing.get();
    }
    String requestedProvider = firstNonBlank(run.getRequestedProvider(), run.getAiProvider());
    boolean pinned = run.isRoutePinned();
    String requestedModel = pinned ? require(run.getRequestedModel(), "pinned model") : null;
    BigDecimal costCap =
        run.getHardCapUsd() == null || run.getHardCapUsd() <= 0
            ? null
            : BigDecimal.valueOf(run.getHardCapUsd());
    return createCustomer(
        LlmExecutionContext.ContextType.PIPELINE_RUN,
        "pipeline",
        "PIPELINE_RUN",
        runId,
        runId,
        userId,
        userId,
        orgId,
        run.getProjectId(),
        requestedProvider,
        requestedModel,
        pinned,
        controlledWorker,
        new LlmExecutionBudget(null, null, null, costCap, null),
        routePolicyKey,
        null);
  }

  /**
   * True for operator-controlled benchmark/SWE-bench/Terminal-Bench workers, whose entire purpose
   * is a fixed-route measurement. Cross-provider failover is suppressed for these runs because a
   * silently re-provisioned attempt would still be reported under the requested route by the
   * campaign artifacts, invalidating any cross-provider comparison built on them.
   *
   * <p>Delegates to the shared {@link BenchmarkSourceType#isControlledWorkerSource} classifier —
   * the exact same set {@code CodeGenEngineImpl.requireTestVerification} consults — so the two
   * controlled-worker behaviours cannot drift apart (RR-1590: {@code TERMINAL_BENCH} previously
   * matched the policy-factory's local literal test in neither site).
   */
  static boolean isControlledWorkerRun(String sourceType) {
    return BenchmarkSourceType.isControlledWorkerSource(sourceType);
  }

  /** Returns empty for a non-run identifier; malformed customer policy still fails closed. */
  public java.util.Optional<LlmExecutionReference> findForRun(String runId) {
    if (runId == null || runId.isBlank() || !runRepository.existsById(runId)) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(forRun(runId));
  }

  /**
   * Dedicated per-turn context for the post-run chat copilot. Mirrors {@link #forRun}'s exact
   * run-owner/org/project/provider/model/pin policy, so BYOK isolation and route policy are
   * identical to the run itself, but carries a unique per-turn source id so it never collides with
   * the run's own (already-terminal) pipeline-run context. Fixes the run-chat idempotency-collision
   * 500 where {@code forRun(runId)} re-created the pipeline tuple every chat turn (Phase-6 F1).
   */
  public LlmExecutionReference forRunChatTurn(String runId, String turnId) {
    var run =
        runRepository
            .findById(require(runId, "runId"))
            .orElseThrow(() -> new IllegalArgumentException("pipeline run not found"));
    String userId = require(run.getCreatedByUserId(), "run owner");
    String orgId = require(run.getOrgId(), "run org");
    requireRunCredentialBinding(run, userId, orgId);
    String requestedProvider = firstNonBlank(run.getRequestedProvider(), run.getAiProvider());
    boolean pinned = run.isRoutePinned();
    String requestedModel = pinned ? require(run.getRequestedModel(), "pinned model") : null;
    String source = "run-chat:" + runId + ":" + require(turnId, "turnId");
    return createCustomer(
        LlmExecutionContext.ContextType.AGENT_TURN,
        "run-chat",
        "RUN_CHAT",
        source,
        source,
        userId,
        userId,
        orgId,
        run.getProjectId(),
        requestedProvider,
        requestedModel,
        pinned,
        RUN_CHAT_TURN_BUDGET,
        "run-chat-v1",
        null);
  }

  /**
   * Dedicated context for one static screen-render surface.
   *
   * <p>A preview is requested after the pipeline is terminal and must not inherit the pipeline
   * context's already-consumed cost ceiling. It still inherits the run owner, host org/project,
   * provider preference, exact model pin and BYOK credential policy. The caller supplies the
   * durable job/surface attempt id, making duplicate execution-context creation idempotent without
   * combining spend from separate re-renders.
   */
  public LlmExecutionReference forRunPreviewSurface(String runId, String renderAttemptId) {
    var run =
        runRepository
            .findById(require(runId, "runId"))
            .orElseThrow(() -> new IllegalArgumentException("pipeline run not found"));
    String userId = require(run.getCreatedByUserId(), "run owner");
    String orgId = require(run.getOrgId(), "run org");
    requireRunCredentialBinding(run, userId, orgId);
    String requestedProvider = firstNonBlank(run.getRequestedProvider(), run.getAiProvider());
    boolean pinned = run.isRoutePinned();
    String requestedModel = pinned ? require(run.getRequestedModel(), "pinned model") : null;
    String source = compactPreviewSourceId(runId, renderAttemptId);
    String correlationId = compactCorrelationId("preview-render", source);
    return createCustomer(
        LlmExecutionContext.ContextType.AGENT_TURN,
        "preview-render",
        "PREVIEW_RENDER",
        source,
        correlationId,
        userId,
        userId,
        orgId,
        run.getProjectId(),
        requestedProvider,
        requestedModel,
        pinned,
        PREVIEW_SURFACE_BUDGET,
        "preview-render-v1",
        null);
  }

  /**
   * Dedicated fixed-route context for one static screen-render surface.
   *
   * <p>The render job resolves its WORKHORSE route once before parallel surface work begins. This
   * overload snapshots that exact physical provider/model as a pin even when the source run itself
   * was provider-preferred, preventing managed-AI substitution or cross-provider failover from
   * making persisted job attribution disagree with the route that produced a bundle.
   */
  public LlmExecutionReference forRunPreviewSurface(
      String runId, String renderAttemptId, ResolvedModel route) {
    var run =
        runRepository
            .findById(require(runId, "runId"))
            .orElseThrow(() -> new IllegalArgumentException("pipeline run not found"));
    String userId = require(run.getCreatedByUserId(), "run owner");
    String orgId = require(run.getOrgId(), "run org");
    requireRunCredentialBinding(run, userId, orgId);
    if (route == null) throw new IllegalArgumentException("preview render route required");
    String requestedProvider = require(route.providerName(), "preview render provider");
    String requestedModel = require(route.getModel(), "preview render model");
    String source = compactPreviewSourceId(runId, renderAttemptId);
    String correlationId = compactCorrelationId("preview-render", source);
    return createCustomer(
        LlmExecutionContext.ContextType.AGENT_TURN,
        "preview-render",
        "PREVIEW_RENDER",
        source,
        correlationId,
        userId,
        userId,
        orgId,
        run.getProjectId(),
        requestedProvider,
        requestedModel,
        true,
        PREVIEW_SURFACE_BUDGET,
        "preview-render-v2-exact-route",
        null);
  }

  public LlmExecutionReference forAgentSession(String sessionId, String turnId) {
    var session =
        agentSessionRepository
            .findById(require(sessionId, "sessionId"))
            .orElseThrow(() -> new IllegalArgumentException("agent session not found"));
    return createCustomer(
        LlmExecutionContext.ContextType.AGENT_TURN,
        "agent-turn",
        "AGENT_SESSION",
        sessionId,
        firstNonBlank(turnId, sessionId),
        session.getUserId(),
        session.getUserId(),
        session.getOrgId(),
        session.getProjectId(),
        session.getDefaultProvider(),
        session.getDefaultModel(),
        session.getDefaultModel() != null && !session.getDefaultModel().isBlank(),
        LlmExecutionBudget.UNBOUNDED,
        "turn:" + firstNonBlank(turnId, "default"),
        null);
  }

  /**
   * Actor-attributed, host-project-aware context for one chat turn. A project collaborator spends
   * against the host project's credential subject while V283 attribution remains on the initiating
   * actor; unscoped chat uses the actor's own org and credentials.
   */
  public LlmExecutionReference forChatTurn(
      String actorUserId,
      String actorOrgId,
      String projectId,
      String feature,
      String sessionId,
      String turnId,
      String correlationId,
      String requestedProvider,
      String requestedModel,
      LlmExecutionBudget budget) {
    String actor = require(actorUserId, "actorUserId");
    String orgId;
    String keySubject;
    if (projectId != null && !projectId.isBlank()) {
      var project =
          projectRepository
              .findById(projectId.trim())
              .orElseThrow(() -> new IllegalArgumentException("project not found"));
      orgId = require(project.getOrgId(), "project org");
      keySubject = require(project.getCreatedBy(), "project credential subject");
    } else {
      orgId = require(actorOrgId, "actorOrgId");
      keySubject = actor;
    }
    String sourceId =
        "chat:"
            + java.util.UUID.nameUUIDFromBytes(
                (require(sessionId, "sessionId") + "\u001f" + require(turnId, "turnId"))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return createCustomer(
        LlmExecutionContext.ContextType.AGENT_TURN,
        require(feature, "feature"),
        "CHAT_TURN",
        sourceId,
        require(correlationId, "correlationId"),
        actor,
        keySubject,
        orgId,
        projectId,
        requestedProvider,
        requestedModel,
        requestedModel != null && !requestedModel.isBlank(),
        budget == null ? LlmExecutionBudget.UNBOUNDED : budget,
        "chat-turn-v1",
        null);
  }

  public LlmExecutionReference forProject(
      String projectId,
      String feature,
      String sourceType,
      String sourceId,
      String correlationId,
      String requestedProvider,
      String requestedModel) {
    return forProject(
        projectId,
        feature,
        sourceType,
        sourceId,
        correlationId,
        requestedProvider,
        requestedModel,
        null,
        LlmExecutionBudget.UNBOUNDED);
  }

  /**
   * Actor-aware project job policy. The initiating member owns usage/audit attribution while the
   * host project creator remains the credential subject for project-scoped customer keys.
   */
  public LlmExecutionReference forProject(
      String projectId,
      String feature,
      String sourceType,
      String sourceId,
      String correlationId,
      String requestedProvider,
      String requestedModel,
      String actorUserId) {
    return forProject(
        projectId,
        feature,
        sourceType,
        sourceId,
        correlationId,
        requestedProvider,
        requestedModel,
        actorUserId,
        LlmExecutionBudget.UNBOUNDED);
  }

  public LlmExecutionReference forProject(
      String projectId,
      String feature,
      String sourceType,
      String sourceId,
      String correlationId,
      String requestedProvider,
      String requestedModel,
      String actorUserId,
      LlmExecutionBudget budget) {
    var project =
        projectRepository
            .findById(require(projectId, "projectId"))
            .orElseThrow(() -> new IllegalArgumentException("project not found"));
    String provider =
        firstNonBlank(
            requestedProvider,
            project.getAiProvider() == null ? null : project.getAiProvider().name());
    return createCustomer(
        LlmExecutionContext.ContextType.PROJECT_JOB,
        feature,
        sourceType,
        sourceId,
        correlationId,
        firstNonBlank(actorUserId, project.getCreatedBy()),
        project.getCreatedBy(),
        project.getOrgId(),
        projectId,
        provider,
        requestedModel,
        requestedModel != null && !requestedModel.isBlank(),
        budget == null ? LlmExecutionBudget.UNBOUNDED : budget,
        "project-route-v1",
        null);
  }

  /** Durable customer policy for project document/code embedding jobs and query vectors. */
  public LlmExecutionReference forProjectEmbedding(
      String projectId,
      String feature,
      String sourceType,
      String sourceId,
      String correlationId,
      String requestedProvider) {
    return forProjectEmbedding(
        projectId, feature, sourceType, sourceId, correlationId, requestedProvider, null);
  }

  /**
   * Project embedding policy attributed to the authenticated initiating member when available.
   * Recovery paths may omit the actor and fall back to the project creator, but interactive
   * upload/search/index entry points must pass it so user-level usage reports remain truthful.
   */
  public LlmExecutionReference forProjectEmbedding(
      String projectId,
      String feature,
      String sourceType,
      String sourceId,
      String correlationId,
      String requestedProvider,
      String actorUserId) {
    var project =
        projectRepository
            .findById(require(projectId, "projectId"))
            .orElseThrow(() -> new IllegalArgumentException("project not found"));
    String provider =
        firstNonBlank(
            requestedProvider,
            project.getAiProvider() == null ? null : project.getAiProvider().name());
    String actor = firstNonBlank(actorUserId, project.getCreatedBy());
    Org org =
        orgRepository
            .findById(project.getOrgId())
            .orElseThrow(() -> new IllegalArgumentException("customer org not found"));
    ApiKeyResolver.CredentialResolution current =
        apiKeyResolver.resolveWithProvenance(project.getCreatedBy());
    String normalizedProvider = normalizeProvider(provider);
    ApiKeyResolver.CredentialOrigin origin = current.originFor(normalizedProvider);
    String embeddingPolicyEpoch =
        "embedding-route-v2:"
            + normalizedProvider
            + ":"
            + String.valueOf(org.getKeySourceMode()).toUpperCase(Locale.ROOT)
            + ":"
            + (origin == null ? "PLATFORM_OR_NONE" : origin.name())
            + ":"
            + Integer.toHexString(String.valueOf(org.getEnabledProviders()).hashCode())
            + ":actor:"
            + stableIdentityComponent(actor);
    return createCustomer(
        LlmExecutionContext.ContextType.EMBEDDING_JOB,
        feature,
        sourceType,
        sourceId,
        correlationId,
        actor,
        project.getCreatedBy(),
        project.getOrgId(),
        projectId,
        provider,
        null,
        false,
        LlmExecutionBudget.UNBOUNDED,
        embeddingPolicyEpoch,
        null);
  }

  /**
   * Embedding contexts retain the initiating actor for truthful usage attribution. The actor must
   * therefore be part of the idempotency namespace: otherwise a later project member refreshing the
   * same repository/source tuple collides with the first member's immutable request fingerprint and
   * fails before any file is inventoried. Keep the durable key opaque rather than persisting a user
   * identifier in the route-policy label.
   */
  private static String stableIdentityComponent(String value) {
    try {
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      return java.util.HexFormat.of()
          .formatHex(
              digest.digest(
                  require(value, "identity value")
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
          .substring(0, 16);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static String compactCorrelationId(String namespace, String identity) {
    return require(namespace, "correlation namespace") + ":" + stableIdentityComponent(identity);
  }

  private static String compactPreviewSourceId(String runId, String renderAttemptId) {
    return "preview-render:"
        + require(runId, "runId")
        + ":"
        + stableIdentityComponent(require(renderAttemptId, "renderAttemptId"));
  }

  public LlmExecutionReference platformInternal(
      String feature, String sourceType, String sourceId, String correlationId) {
    Set<String> allowed = new LinkedHashSet<>();
    Map<String, LlmPolicySnapshot.CredentialSource> sources = new LinkedHashMap<>();
    for (String provider : PROVIDERS) {
      if (platformKeyPresent(provider)) {
        allowed.add(provider);
        sources.put(provider, LlmPolicySnapshot.CredentialSource.PLATFORM);
      }
    }
    if (allowed.isEmpty()) {
      throw new IllegalStateException("No platform provider credentials are configured");
    }
    LlmPolicySnapshot policy =
        new LlmPolicySnapshot(
            LlmPolicySnapshot.KeySourceMode.SYSTEM,
            null,
            allowed,
            modelsFor(allowed),
            sources,
            Map.of(),
            null,
            null,
            LlmPolicySnapshot.RoutingMode.PROVIDER_PREFERRED,
            false,
            true,
            1);
    LlmExecutionContext context =
        contextService.create(
            new LlmExecutionContextCreateRequest(
                LlmExecutionContext.ContextType.INTERNAL_TEST,
                feature,
                sourceType,
                sourceId,
                null,
                correlationId,
                LlmExecutionScope.platformInternal(),
                null,
                null,
                null,
                null,
                policy,
                LlmExecutionBudget.UNBOUNDED,
                1,
                "platform-route-v1",
                null));
    return new LlmExecutionReference(context.getId(), context.scope());
  }

  private LlmExecutionReference createCustomer(
      LlmExecutionContext.ContextType contextType,
      String feature,
      String sourceType,
      String sourceId,
      String correlationId,
      String userId,
      String keySubjectUserId,
      String orgId,
      String projectId,
      String requestedProvider,
      String requestedModel,
      boolean exact,
      LlmExecutionBudget budget,
      String idempotencyKey,
      java.time.Instant expiresAt) {
    return createCustomer(
        contextType,
        feature,
        sourceType,
        sourceId,
        correlationId,
        userId,
        keySubjectUserId,
        orgId,
        projectId,
        requestedProvider,
        requestedModel,
        exact,
        false,
        budget,
        idempotencyKey,
        expiresAt);
  }

  private LlmExecutionReference createCustomer(
      LlmExecutionContext.ContextType contextType,
      String feature,
      String sourceType,
      String sourceId,
      String correlationId,
      String userId,
      String keySubjectUserId,
      String orgId,
      String projectId,
      String requestedProvider,
      String requestedModel,
      boolean exact,
      boolean controlledWorker,
      LlmExecutionBudget budget,
      String idempotencyKey,
      java.time.Instant expiresAt) {
    require(userId, "userId");
    require(keySubjectUserId, "keySubjectUserId");
    String tenantOrgId = require(orgId, "orgId");
    ApiKeyResolver.CredentialResolution credentials =
        apiKeyResolver.resolveWithProvenance(keySubjectUserId);
    String credentialOrgId = require(credentials.orgId(), "credentialOrgId");
    Org credentialOrg =
        orgRepository
            .findById(credentialOrgId)
            .orElseThrow(() -> new IllegalArgumentException("credential policy org not found"));
    // 2026-08-05 — snapshot the RESOLVER's tier-normalized mode, not the raw org column.
    // LlmExecutionDispatchGuard revalidates against ApiKeyResolver's normalized value (a non-BYOK
    // tier forces SYSTEM even when a stale HYBRID/OWN_KEYS marker remains on the row); snapshotting
    // the raw column made every dispatch for such orgs fail POLICY_STALE ("key-source policy
    // changed") on a policy that never actually changed.
    LlmPolicySnapshot.KeySourceMode mode = keySourceMode(credentials.keys().keySourceMode());
    Set<String> enabled = parseProviders(credentialOrg.getEnabledProviders());
    Set<String> allowed = new LinkedHashSet<>();
    Map<String, LlmPolicySnapshot.CredentialSource> sources = new LinkedHashMap<>();
    for (String provider : PROVIDERS) {
      if (enabled != null && !enabled.contains(provider)) continue;
      ApiKeyResolver.CredentialOrigin origin = credentials.originFor(provider);
      if (mode != LlmPolicySnapshot.KeySourceMode.SYSTEM && origin != null) {
        allowed.add(provider);
        sources.put(
            provider,
            origin == ApiKeyResolver.CredentialOrigin.USER
                ? LlmPolicySnapshot.CredentialSource.USER
                : LlmPolicySnapshot.CredentialSource.ORG);
      } else if (mode != LlmPolicySnapshot.KeySourceMode.OWN_KEYS && platformKeyPresent(provider)) {
        allowed.add(provider);
        sources.put(provider, LlmPolicySnapshot.CredentialSource.PLATFORM);
      }
    }
    if (allowed.isEmpty()) {
      throw new IllegalStateException("No provider is both allowed and credentialed for this org");
    }
    String provider = normalizeProvider(requestedProvider);
    if (provider == null) {
      if (allowed.size() != 1) {
        throw new IllegalStateException("Customer LLM work requires an explicit provider");
      }
      provider = allowed.iterator().next();
    }
    if (!allowed.contains(provider)) {
      throw new IllegalStateException(
          "Requested provider is not allowed or credentialed: " + provider);
    }
    boolean ownKeysProviderLock = isOwnKeys(mode);
    if (ownKeysProviderLock) {
      LlmPolicySnapshot.CredentialSource selectedSource = sources.get(provider);
      allowed = new LinkedHashSet<>(Set.of(provider));
      sources = new LinkedHashMap<>(Map.of(provider, selectedSource));
    }
    String model = requestedModel == null ? null : modelRegistry.canonicalize(requestedModel);
    if (model != null) validateModel(provider, model);
    LlmPolicySnapshot policy =
        new LlmPolicySnapshot(
            mode,
            keySubjectUserId,
            allowed,
            modelsFor(allowed),
            sources,
            Map.of(),
            provider,
            model,
            exact
                ? LlmPolicySnapshot.RoutingMode.EXACT
                : LlmPolicySnapshot.RoutingMode.PROVIDER_PREFERRED,
            exact,
            // Leading BYOK term is deliberately separate and must not be reordered or
            // short-circuited: if an OWN_KEYS run regained failover eligibility, the only remaining
            // defence would be ByokContextUnavailableException, which fires on key source, not on
            // provider policy.
            !exact && !ownKeysProviderLock && !controlledWorker,
            ownKeysProviderLock ? 2 : 1);
    LlmExecutionScope scope = LlmExecutionScope.customer(tenantOrgId);
    LlmExecutionContext context =
        contextService.create(
            new LlmExecutionContextCreateRequest(
                contextType,
                feature,
                sourceType,
                sourceId,
                null,
                correlationId,
                scope,
                userId,
                credentialOrgId,
                projectId,
                null,
                policy,
                budget,
                3,
                idempotencyKey,
                expiresAt));
    return new LlmExecutionReference(context.getId(), scope);
  }

  private Map<String, List<String>> modelsFor(Set<String> providers) {
    Map<String, List<String>> byProvider = new LinkedHashMap<>();
    for (String provider : providers) byProvider.put(provider, new ArrayList<>());
    modelRegistry
        .allModels()
        .forEach(
            (model, config) -> {
              String ownerProvider = normalizeProvider(config.getProvider());
              byProvider.forEach(
                  (provider, models) -> {
                    LlmProvider routeProvider =
                        LlmProvider.valueOf(provider.toUpperCase(Locale.ROOT));
                    if (ModelAccessService.canRouteModel(routeProvider, model, ownerProvider)) {
                      models.add(model);
                    }
                  });
            });
    byProvider.replaceAll((provider, models) -> List.copyOf(models));
    return byProvider;
  }

  private void requireRunCredentialBinding(
      com.navneet.executespec.state.entity.PipelineRun run, String userId, String tenantOrgId) {
    String durableBillingOrgId = firstNonBlank(run.getBillingOrgId(), tenantOrgId);
    String currentCredentialOrgId =
        require(apiKeyResolver.resolveWithProvenance(userId).orgId(), "credential org");
    if (!durableBillingOrgId.equals(currentCredentialOrgId)) {
      throw new IllegalStateException(
          "Run credential subject no longer belongs to the durable billing org");
    }
  }

  private void validateModel(String provider, String model) {
    if (!modelRegistry.isKnownModel(model)) {
      throw new IllegalStateException("Unknown requested model: " + model);
    }
    String modelProvider = normalizeProvider(modelRegistry.providerFor(model));
    LlmProvider routeProvider = LlmProvider.valueOf(provider.toUpperCase(Locale.ROOT));
    if (!ModelAccessService.canRouteModel(routeProvider, model, modelProvider)) {
      throw new IllegalStateException("Requested model does not belong to provider " + provider);
    }
  }

  boolean platformKeyPresent(String provider) {
    String key =
        switch (normalizeProvider(provider)) {
          case "anthropic" -> platformAnthropicKey;
          case "openai" -> platformOpenAiKey;
          case "gemini" -> platformGeminiKey;
          case "openrouter" -> platformOpenRouterKey;
          case "deepseek" -> platformDeepSeekKey;
          case "fireworks" -> platformFireworksKey;
          default -> null;
        };
    return key != null && !key.isBlank();
  }

  private static LlmPolicySnapshot.KeySourceMode keySourceMode(String value) {
    try {
      return LlmPolicySnapshot.KeySourceMode.valueOf(
          value == null ? "HYBRID" : value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ignored) {
      throw new IllegalStateException("Invalid org key source mode");
    }
  }

  private static boolean isOwnKeys(LlmPolicySnapshot.KeySourceMode keySourceMode) {
    return keySourceMode == LlmPolicySnapshot.KeySourceMode.OWN_KEYS;
  }

  private static Set<String> parseProviders(String csv) {
    if (csv == null || csv.isBlank()) return null;
    Set<String> values = new LinkedHashSet<>();
    for (String value : csv.split(",")) {
      String normalized = normalizeProvider(value);
      if (normalized != null) values.add(normalized);
    }
    return values;
  }

  static String normalizeProvider(String provider) {
    if (provider == null || provider.isBlank()) return null;
    String normalized = provider.trim().toLowerCase(Locale.ROOT);
    return "google".equals(normalized) ? "gemini" : normalized;
  }

  private static String require(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    return value.trim();
  }

  private static String firstNonBlank(String first, String second) {
    if (first != null && !first.isBlank()) return first;
    return second != null && !second.isBlank() ? second : null;
  }
}
