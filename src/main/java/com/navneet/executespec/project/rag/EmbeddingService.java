package com.navneet.executespec.project.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;

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

  private final EmbeddingPolicyResolver policyResolver;
  private final LlmExecutionPolicyFactory executionPolicyFactory;
  private final LlmExecutionDispatchGuard dispatchGuard;
  private final OpenAiEmbeddingTransport transport;
  private final RunStepUsageRecorder usageRecorder;
  private final ManagedAiUsageService managedAiUsageService;

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

  public List<float[]> embedForProject(
      String projectId, List<String> texts, String feature, String sourceType, String sourceId) {
    return embedForProject(projectId, texts, feature, sourceType, sourceId, null);
  }

  public List<float[]> embedForProject(
      String projectId,
      List<String> texts,
      String feature,
      String sourceType,
      String sourceId,
      String actorUserId) {
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
    LlmExecutionDispatchGuard.ResolvedDispatch dispatch;
    try {
      dispatch = dispatchGuard.authorizeProviderCapability(reference, PROVIDER);
    } catch (LlmDispatchPolicyException rejected) {
      throw new EmbeddingUnavailableException(rejected.reason().name(), rejected);
    }
    String key = dispatch.apiKey();
    if (key == null || key.isBlank()) {
      throw new EmbeddingUnavailableException("AUTHORIZED_OPENAI_CREDENTIAL_UNAVAILABLE");
    }

    String runId = usageRunId(sourceId);
    RunKeyContext managedKeys = managedAiContext(dispatch);

    List<float[]> vectors = new ArrayList<>(texts.size());
    int batchNumber = 0;
    for (int from = 0; from < texts.size(); from += BATCH_SIZE) {
      batchNumber++;
      List<String> batch = texts.subList(from, Math.min(from + BATCH_SIZE, texts.size()));
      String taskId = "embedding.batch." + batchNumber;
      // Each batch is a distinct provider charge. Re-authorize immediately before every physical
      // attempt so a large document cannot spend N batches under one stale admission decision.
      managedAiUsageService.authorizeFixedCapabilityCall(
          runId, PROVIDER, model, feature, managedKeys, dispatch.keySource());
      var attempt =
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
      long started = System.currentTimeMillis();
      OpenAiEmbeddingTransport.Result result;
      try {
        result = transport.embed(key, model, batch);
      } catch (RuntimeException providerFailure) {
        long latency = Math.max(0, System.currentTimeMillis() - started);
        usageRecorder.recordProviderCapabilityFailure(
            attempt,
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
              attempt, runId, taskId, "EmbeddingService", "embedding.batch", latency, callUsage);
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
            managedKeys,
            dispatch.keySource());
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
    }
    if (vectors.size() != texts.size()) {
      throw new IllegalStateException("Embedding result count mismatch");
    }
    return List.copyOf(vectors);
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
    return embedForProject(projectId, List.of(text), feature, sourceType, sourceId, actorUserId)
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

  private static String usageRunId(String sourceId) {
    String safe = requireSource(sourceId).replaceAll("[^A-Za-z0-9._:-]", "_");
    String value = "embedding:" + safe;
    return value.substring(0, Math.min(96, value.length()));
  }

  private static String requireSource(String sourceId) {
    if (sourceId == null || sourceId.isBlank()) {
      throw new IllegalArgumentException("embedding sourceId required");
    }
    String normalized = sourceId.trim();
    if (normalized.length() <= 150) return normalized;
    try {
      String hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(normalized.getBytes(StandardCharsets.UTF_8)));
      return normalized.substring(0, 80) + ":sha256:" + hash;
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
