package com.navneet.executespec.cost;

import java.util.UUID;

import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.executioncontext.LlmExecutionContextService;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Resolves tenant dimensions without fuzzy matching any source or correlation identifier. */
@Component
@RequiredArgsConstructor
public class LlmUsageAttributionResolver {

  private final LlmExecutionContextService executionContextService;
  private final PipelineRunRepository pipelineRunRepository;
  private final AgentSessionRepository agentSessionRepository;

  public Attribution resolve(LlmRequest request) {
    if (request == null || request.context() == null) return Attribution.unattributed(null);
    String runId = trimToNull(request.context().runId());
    String feature = firstNonBlank(request.agentRole(), "LLM_CALL");

    if (request.llmExecutionContextId() != null) {
      var durable =
          executionContextService
              .load(request.llmExecutionScope(), request.llmExecutionContextId())
              .orElse(null);
      if (durable == null) {
        return new Attribution(
            null,
            null,
            null,
            null,
            null,
            feature,
            "INVALID_EXECUTION_CONTEXT",
            request.llmExecutionContextId(),
            request.llmExecutionContextId());
      }
      return new Attribution(
          durable.getId(),
          durable.getOrgId(),
          durable.getUserId(),
          durable.getProjectId(),
          durable.getRepoId(),
          durable.getFeature(),
          durable.getSourceType(),
          durable.getSourceId(),
          durable.getCorrelationId());
    }

    // Transitional compatibility is exact-primary-key only. Never infer from V282 source_id or
    // correlation_id: neither is globally tenant-unique.
    if (runId != null) {
      var pipeline = pipelineRunRepository.findById(runId).orElse(null);
      if (pipeline != null) {
        return new Attribution(
            null,
            pipeline.getOrgId(),
            pipeline.getCreatedByUserId(),
            pipeline.getProjectId(),
            null,
            feature,
            "PIPELINE_RUN",
            runId,
            runId);
      }
      var session = agentSessionRepository.findById(runId).orElse(null);
      if (session != null) {
        return new Attribution(
            null,
            session.getOrgId(),
            session.getUserId(),
            session.getProjectId(),
            null,
            feature,
            "AGENT_SESSION",
            runId,
            runId);
      }
    }
    return Attribution.unattributed(runId);
  }

  /** Exact attribution for governed non-chat provider capabilities such as embeddings. */
  public Attribution resolve(LlmExecutionReference reference) {
    if (reference == null) return Attribution.unattributed(null);
    var durable =
        executionContextService.load(reference.scope(), reference.contextId()).orElse(null);
    if (durable == null) {
      return new Attribution(
          null,
          null,
          null,
          null,
          null,
          "LLM_CAPABILITY",
          "INVALID_EXECUTION_CONTEXT",
          reference.contextId(),
          reference.contextId());
    }
    return new Attribution(
        durable.getId(),
        durable.getOrgId(),
        durable.getUserId(),
        durable.getProjectId(),
        durable.getRepoId(),
        durable.getFeature(),
        durable.getSourceType(),
        durable.getSourceId(),
        durable.getCorrelationId());
  }

  public record Attribution(
      String llmExecutionContextId,
      String orgId,
      String userId,
      String projectId,
      UUID repoId,
      String feature,
      String sourceType,
      String sourceId,
      String correlationId) {
    static Attribution unattributed(String sourceId) {
      return new Attribution(
          null, null, null, null, null, "LLM_CALL", "LEGACY_RUN_SCOPE", sourceId, sourceId);
    }
  }

  private static String firstNonBlank(String value, String fallback) {
    String normalized = trimToNull(value);
    return normalized == null ? fallback : normalized;
  }

  private static String trimToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
