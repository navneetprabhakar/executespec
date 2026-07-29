package com.navneet.executespec.project.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import jakarta.annotation.PostConstruct;

import com.navneet.executespec.agent.RunKeyContext;
import com.navneet.executespec.billing.service.ManagedAiUsageService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.cost.RunStepUsageRecorder;
import com.navneet.executespec.executioncontext.LlmDispatchPolicyException;
import com.navneet.executespec.executioncontext.LlmExecutionDispatchGuard;
import com.navneet.executespec.executioncontext.LlmExecutionOperationAdmissionPolicy;
import com.navneet.executespec.executioncontext.LlmExecutionOperationAuthority;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.PhysicalProviderAttemptAdmissionService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Governed 1536-dimensional project embedding dispatcher.
 *
 * <p>The pgvector schema is intentionally pinned to OpenAI {@value #DEFAULT_MODEL}. A project whose
 * selected provider is not OpenAI, or whose V282 policy does not authorize a current OpenAI key,
 * receives an explicit lexical-only result. No customer path reads the global OpenAI credential
 * until the durable context authorizes PLATFORM as its exact key source.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingService {

  public static final String DEFAULT_MODEL = "text-embedding-3-small";
  public static final int VECTOR_DIMENSIONS = 1536;
  public static final String PROVIDER = "openai";
  private static final int BATCH_SIZE = 100;
  // OpenAiEmbeddingTransport bounds connect to 5s and response reads to 60s by default. Atomic
  // admission reserves that complete physical-call window plus a small terminal-recording margin.
  private static final Duration TRANSPORT_TIMEOUT = Duration.ofSeconds(65);
  private static final Duration TRANSPORT_SAFETY_MARGIN = Duration.ofSeconds(15);

  private final EmbeddingPolicyResolver policyResolver;
  private final LlmExecutionPolicyFactory executionPolicyFactory;
  private final LlmExecutionDispatchGuard dispatchGuard;
  private final OpenAiEmbeddingTransport transport;
  private final RunStepUsageRecorder usageRecorder;
  private final ManagedAiUsageService managedAiUsageService;
  private final LlmExecutionOperationAdmissionPolicy operationAdmissionPolicy;
  private final PhysicalProviderAttemptAdmissionService physicalAttemptAdmissionService;

  /**
   * RR-392 (2026-07-20) — the embedding transport is a real provider-spending path that carried no
   * live-LLM kill switch at all, so an emergency cost shutdown or a misconfigured test could still
   * bill OpenAI here. It now passes through the same shared authorization as chat dispatch.
   */
  private final com.navneet.executespec.agent.ProviderDispatchAuthorization
      providerDispatchAuthorization;

  @Value("${project.rag.embedding.openai.input-cost-per-million-usd:0.02}")
  private double inputCostPerMillionUsd = 0.02d;

  @Value("${project.rag.embedding.openai.model:text-embedding-3-small}")
  private String model = DEFAULT_MODEL;

  @Value("${project.rag.embedding.openai.dimensions:1536}")
  private int dimensions = VECTOR_DIMENSIONS;

  /**
   * Vector columns cannot safely mix dimensions or model families. Refuse startup instead of
   * silently comparing newly generated query vectors with an older persisted corpus.
   */
  @PostConstruct
  void validateVectorContract() {
    if (!DEFAULT_MODEL.equals(model) || dimensions != VECTOR_DIMENSIONS) {
      throw new IllegalStateException(
          "Project RAG vector contract is pinned to "
              + DEFAULT_MODEL
              + "/"
              + VECTOR_DIMENSIONS
              + "; migrate persisted vector provenance before changing it");
    }
  }

  public EmbeddingPolicyResolver.Availability availability(String projectId) {
    return policyResolver.resolve(projectId);
  }

  /**
   * RR-676 — a hook invoked after each embedding batch completes so a caller holding a fenced
   * processing lease can renew it between batches (a large document embeds across many batches
   * under a fixed lease that would otherwise expire mid-call). Returning {@code false} signals that
   * the caller has lost ownership; the dispatcher stops before spending the next batch.
   */
  @FunctionalInterface
  public interface BatchProgressCallback {
    boolean onBatchComplete(int completedBatches, int totalBatches);
  }

  public List<float[]> embedForProject(
      String projectId, List<String> texts, String feature, String sourceType, String sourceId) {
    return embedForProject(projectId, texts, feature, sourceType, sourceId, null, null);
  }

  public List<float[]> embedForProject(
      String projectId,
      List<String> texts,
      String feature,
      String sourceType,
      String sourceId,
      String actorUserId) {
    return embedForProject(projectId, texts, feature, sourceType, sourceId, actorUserId, null);
  }

  public List<float[]> embedForProject(
      String projectId,
      List<String> texts,
      String feature,
      String sourceType,
      String sourceId,
      String actorUserId,
      BatchProgressCallback progress) {
    return embedForProject(
        projectId, texts, feature, sourceType, sourceId, actorUserId, progress, null);
  }

  /**
   * Operation-authority-capable embedding path.
   *
   * <p>Logical roots opt in by passing the opaque authority issued for {@code reference}. Rollout
   * policy remains the sole switch: LEGACY preserves the existing write-ahead path, SHADOW falls
   * back only when authority is absent, and ENFORCED rejects missing authority before transport.
   * This method owns physical attempts only; it never completes the logical operation.
   */
  public List<float[]> embedForProject(
      String projectId,
      List<String> texts,
      String feature,
      String sourceType,
      String sourceId,
      String actorUserId,
      BatchProgressCallback progress,
      LlmExecutionOperationAuthority operationAuthority) {
    if (texts == null || texts.isEmpty()) return List.of();
    var availability = policyResolver.resolve(projectId);
    if (!availability.semanticSearchAvailable()) {
      throw new EmbeddingUnavailableException(availability.reason());
    }

    var reference =
        executionPolicyFactory.forProjectEmbedding(
            projectId,
            feature,
            sourceType,
            requireSource(sourceId),
            requireSource(sourceId),
            PROVIDER,
            actorUserId);
    String runId = usageRunId(sourceId);
    LlmExecutionOperationAdmissionPolicy.Decision admissionDecision =
        operationAdmissionPolicy.decide(reference, operationAuthority);
    if (admissionDecision.rejectsBeforeTransport()) {
      throw new IllegalStateException(
          "Embedding operation admission rejected before transport: " + admissionDecision.reason());
    }

    List<float[]> vectors = new ArrayList<>(texts.size());
    int totalBatches = (texts.size() + BATCH_SIZE - 1) / BATCH_SIZE;
    int batchNumber = 0;
    for (int from = 0; from < texts.size(); from += BATCH_SIZE) {
      batchNumber++;
      List<String> batch = texts.subList(from, Math.min(from + BATCH_SIZE, texts.size()));
      String taskId = "embedding.batch." + batchNumber;
      BatchAdmission batchAdmission =
          admissionDecision.usesAtomicV1()
              ? admitAtomicBatch(reference, operationAuthority, runId, taskId, feature, batchNumber)
              : admitLegacyBatch(reference, runId, taskId, feature, batchNumber);
      long started = System.currentTimeMillis();
      OpenAiEmbeddingTransport.Result result;
      try {
        result = transport.embed(batchAdmission.apiKey(), model, batch);
      } catch (RuntimeException providerFailure) {
        long latency = Math.max(0, System.currentTimeMillis() - started);
        usageRecorder.recordProviderCapabilityFailure(
            batchAdmission.attempt(),
            runId,
            taskId,
            "EmbeddingService",
            "embedding.batch",
            latency,
            providerFailure);
        throw providerFailure;
      }
      long latency = Math.max(0, System.currentTimeMillis() - started);
      long inputTokens =
          result.inputTokens() > 0 ? result.inputTokens() : estimatedInputTokens(batch);
      double cost = inputTokens * inputCostPerMillionUsd / 1_000_000d;
      RunCostTracker.CallUsage callUsage =
          new RunCostTracker.CallUsage(PROVIDER, model, inputTokens, 0, 0, 0, cost, batchNumber, 0);
      String usageEventId =
          usageRecorder.recordProviderCapabilitySuccess(
              batchAdmission.attempt(),
              runId,
              taskId,
              "EmbeddingService",
              "embedding.batch",
              latency,
              callUsage);
      try {
        managedAiUsageService.recordSuccessfulCall(
            usageEventId,
            runId,
            feature,
            feature,
            "embedding.batch",
            PROVIDER,
            model,
            callUsage,
            batchAdmission.managedKeys(),
            batchAdmission.keySource());
      } catch (RuntimeException accountingFailure) {
        // The provider succeeded and the canonical physical-attempt row already exists. Do not
        // misclassify or repeat the provider call because the secondary managed-credit projection
        // failed; reconciliation will expose the discrepancy for operator repair.
        log.error(
            "Managed-AI embedding projection failed run={} feature={} batch={}",
            runId,
            feature,
            batchNumber);
      }
      vectors.addAll(result.vectors());
      // RR-676 — renew the caller's fenced lease between batches and cancel promptly if ownership
      // was lost (delete / takeover), so a large multi-batch embedding cannot expire its own lease
      // and then fail to publish, and a lost owner stops spending on further batches.
      if (progress != null && !progress.onBatchComplete(batchNumber, totalBatches)) {
        throw new EmbeddingOwnershipLostException(feature, batchNumber, totalBatches);
      }
    }
    if (vectors.size() != texts.size()) {
      throw new IllegalStateException("Embedding result count mismatch");
    }
    return List.copyOf(vectors);
  }

  private BatchAdmission admitLegacyBatch(
      com.navneet.executespec.executioncontext.LlmExecutionReference reference,
      String runId,
      String taskId,
      String feature,
      int batchNumber) {
    // RR-915 — legacy rollout keeps the established ordering and version-0 intent writer exactly
    // as-is. This path is removed only after every embedding logical root carries authority.
    LlmExecutionDispatchGuard.ResolvedDispatch dispatch;
    try {
      dispatch = dispatchGuard.authorizeProviderCapability(reference, PROVIDER);
    } catch (LlmDispatchPolicyException rejected) {
      throw new EmbeddingUnavailableException(rejected.reason().name(), rejected);
    }
    String key = requireApiKey(dispatch.apiKey());
    RunKeyContext managedKeys = managedAiContext(dispatch);
    managedAiUsageService.authorizeFixedCapabilityCall(
        runId, PROVIDER, model, feature, managedKeys, dispatch.keySource());
    RunStepUsageRecorder.AttemptHandle attempt =
        usageRecorder.prepareProviderCapabilityAttempt(
            reference,
            runId,
            taskId,
            "EmbeddingService",
            "embedding.batch",
            PROVIDER,
            model,
            dispatch.keySource().name(),
            batchNumber);
    providerDispatchAuthorization.authorize(PROVIDER, model, runId);
    return new BatchAdmission(attempt, key, dispatch.keySource(), managedKeys);
  }

  private BatchAdmission admitAtomicBatch(
      com.navneet.executespec.executioncontext.LlmExecutionReference reference,
      LlmExecutionOperationAuthority operationAuthority,
      String runId,
      String taskId,
      String feature,
      int batchNumber) {
    // The global kill switch and managed-credit preflight both run before the atomic service
    // drafts or persists an intent. The central service then reauthorizes once more inside the
    // admission sequence; only its fresh key/source may reach the transport.
    providerDispatchAuthorization.authorize(PROVIDER, model, runId);
    LlmExecutionDispatchGuard.ResolvedDispatch preflight;
    try {
      preflight = dispatchGuard.reauthorizeProviderCapability(reference, PROVIDER);
    } catch (LlmDispatchPolicyException rejected) {
      throw new EmbeddingUnavailableException(rejected.reason().name(), rejected);
    }
    RunKeyContext managedKeys = managedAiContext(preflight);
    managedAiUsageService.authorizeFixedCapabilityCall(
        runId, PROVIDER, model, feature, managedKeys, preflight.keySource());

    PhysicalProviderAttemptAdmissionService.AdmittedPhysicalAttempt admitted;
    try {
      admitted =
          physicalAttemptAdmissionService.admitCapabilityAttempt(
              new PhysicalProviderAttemptAdmissionService.CapabilityAttempt(
                  reference,
                  operationAuthority,
                  runId,
                  taskId,
                  "EmbeddingService",
                  "embedding.batch",
                  PROVIDER,
                  model,
                  batchNumber,
                  0,
                  TRANSPORT_TIMEOUT,
                  TRANSPORT_SAFETY_MARGIN));
    } catch (LlmDispatchPolicyException rejected) {
      // Reauthorization occurs inside the central service before it drafts/persists the intent.
      throw new EmbeddingUnavailableException(rejected.reason().name(), rejected);
    }
    return new BatchAdmission(
        admitted.handle(),
        requireApiKey(admitted.apiKey()),
        admitted.keySource(),
        managedAiContext(preflight, admitted.apiKey(), admitted.keySource()));
  }

  private static String requireApiKey(String key) {
    if (key == null || key.isBlank()) {
      throw new EmbeddingUnavailableException("AUTHORIZED_OPENAI_CREDENTIAL_UNAVAILABLE");
    }
    return key;
  }

  public float[] embedQueryForProject(
      String projectId, String text, String feature, String sourceType, String sourceId) {
    return embedQueryForProject(projectId, text, feature, sourceType, sourceId, null);
  }

  public float[] embedQueryForProject(
      String projectId,
      String text,
      String feature,
      String sourceType,
      String sourceId,
      String actorUserId) {
    return embedQueryForProject(projectId, text, feature, sourceType, sourceId, actorUserId, null);
  }

  /** Operation-authority-capable query-vector variant for durable chat/retrieval roots. */
  public float[] embedQueryForProject(
      String projectId,
      String text,
      String feature,
      String sourceType,
      String sourceId,
      String actorUserId,
      LlmExecutionOperationAuthority operationAuthority) {
    return embedForProject(
            projectId,
            List.of(text),
            feature,
            sourceType,
            sourceId,
            actorUserId,
            null,
            operationAuthority)
        .get(0);
  }

  public String model() {
    return model;
  }

  /**
   * Legacy context-free dispatch is prohibited; retained only to make stale callers fail closed.
   */
  @Deprecated(forRemoval = true)
  public List<float[]> embed(List<String> texts) {
    throw new EmbeddingUnavailableException("PROJECT_EXECUTION_CONTEXT_REQUIRED");
  }

  /**
   * Legacy context-free dispatch is prohibited; retained only to make stale callers fail closed.
   */
  @Deprecated(forRemoval = true)
  public float[] embedQuery(String text) {
    throw new EmbeddingUnavailableException("PROJECT_EXECUTION_CONTEXT_REQUIRED");
  }

  private static long estimatedInputTokens(List<String> texts) {
    long chars = 0;
    for (String text : texts) {
      if (text != null) chars = Math.min(Long.MAX_VALUE - chars, chars + text.length());
    }
    return Math.max(1, (chars + 3) / 4);
  }

  private static RunKeyContext managedAiContext(
      LlmExecutionDispatchGuard.ResolvedDispatch dispatch) {
    var context = dispatch.context();
    if (context == null) return null;
    RunKeyContext.Source source = dispatch.keySource();
    return new RunKeyContext(
        null,
        null,
        null,
        null,
        null,
        null,
        source,
        source,
        source,
        source,
        source,
        source,
        context.getKeySubjectUserId(),
        context.getOrgId());
  }

  private static RunKeyContext managedAiContext(
      LlmExecutionDispatchGuard.ResolvedDispatch preflight,
      String apiKey,
      RunKeyContext.Source keySource) {
    var context = preflight.context();
    if (context == null) return null;
    return new RunKeyContext(
        null,
        apiKey,
        null,
        null,
        null,
        null,
        keySource,
        keySource,
        keySource,
        keySource,
        keySource,
        keySource,
        context.getKeySubjectUserId(),
        context.getOrgId());
  }

  private record BatchAdmission(
      RunStepUsageRecorder.AttemptHandle attempt,
      String apiKey,
      RunKeyContext.Source keySource,
      RunKeyContext managedKeys) {}

  private static String usageRunId(String sourceId) {
    String safe = requireSource(sourceId).replaceAll("[^A-Za-z0-9._:-]", "_");
    String value = "embedding:" + safe;
    return value.substring(0, Math.min(96, value.length()));
  }

  static String requireSource(String sourceId) {
    if (sourceId == null || sourceId.isBlank()) {
      throw new IllegalArgumentException("embedding sourceId required");
    }
    String normalized = sourceId.trim();
    if (normalized.length() <= 96) return normalized;
    try {
      String hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(normalized.getBytes(StandardCharsets.UTF_8)));
      return normalized.substring(0, 23) + ":sha256:" + hash;
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
