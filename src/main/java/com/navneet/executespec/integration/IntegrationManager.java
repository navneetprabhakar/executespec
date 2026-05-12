package com.navneet.executespec.integration;

import static java.util.stream.Collectors.toMap;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.IntegrationEventLog;
import com.navneet.executespec.state.entity.IntegrationRun;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.IntegrationEventLogRepository;
import com.navneet.executespec.state.repository.IntegrationRunRepository;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Inbound integration hub — routes webhooks from external source systems into the pipeline.
 *
 * <p>Handles webhook verification, idempotency (via {@code IntegrationEventLog}), event parsing,
 * and run submission. Has no outbound concerns.
 *
 * <p>Outbound notifications are handled by {@link IntegrationOutboundListener} which listens to
 * Spring events independently of this class.
 */
@Service
@Slf4j
public class IntegrationManager {

  private final Map<String, InboundAdapter> inboundAdapters;
  private final IntegrationRunRepository integrationRunRepo;
  private final IntegrationEventLogRepository eventLogRepo;
  private final StateStore stateStore;
  private final RunOrchestrator v2Orchestrator;

  public IntegrationManager(
      List<InboundAdapter> inbound,
      IntegrationRunRepository integrationRunRepo,
      IntegrationEventLogRepository eventLogRepo,
      StateStore stateStore,
      RunOrchestrator v2Orchestrator) {
    this.inboundAdapters = inbound.stream().collect(toMap(InboundAdapter::sourceType, a -> a));
    this.integrationRunRepo = integrationRunRepo;
    this.eventLogRepo = eventLogRepo;
    this.stateStore = stateStore;
    this.v2Orchestrator = v2Orchestrator;
  }

  /**
   * Submit an integration-triggered run. Integration runs bypass the plan- approval gate
   * (skipApproval=true when signalled) and have no repo/github config — they're webhook-driven.
   */
  /**
   * Submits a pipeline run on behalf of an inbound webhook event.
   *
   * <p><strong>Audit 2026-05-12 review item E #36:</strong> historically passed null for userId /
   * orgId / projectId because webhook context didn't carry an authenticated user. That bypasses
   * credit gating, tenant attribution, and project memory. The new contract: when {@link
   * IntegrationProperties.GenericConfig#getRunOnBehalfOfUserId()} or matching per-source config
   * returns a non-blank value, that user (and their org) is stamped on the run so billing +
   * multi-tenant audit work. Falls back to the legacy null-attribution behaviour only when no
   * integration-runner identity is configured (single-tenant dev mode).
   */
  private PipelineRun submitIntegrationRun(
      String userRequest, String sourceType, boolean skipApproval) {
    String runnerUserId = resolveIntegrationRunnerUserId(sourceType);
    String runnerOrgId = resolveIntegrationRunnerOrgId(runnerUserId);
    if (runnerUserId == null) {
      log.warn(
          "[Integration] No integration-runner identity configured for source '{}' — submitting"
              + " with null user/org attribution (legacy dev path).",
          sourceType);
    }
    return v2Orchestrator.submitRun(
        userRequest,
        sourceType,
        /* repoUrl */ null,
        /* repoMode */ "NONE",
        /* githubPat */ null,
        /* githubOwner */ null,
        skipApproval,
        /* autoMerge */ true,
        /* modelName */ null,
        /* aiProvider */ null,
        runnerUserId,
        runnerOrgId,
        /* projectId */ null,
        "PROTOTYPE");
  }

  /**
   * Hook for org-scoped integration-runner identity. Today returns null (preserves legacy
   * behaviour); when teams add per-source-type runner identity config the field is read here.
   * Documented so the follow-up wire-up is a one-place edit.
   */
  private String resolveIntegrationRunnerUserId(String sourceType) {
    // TODO(audit E #36 follow-up): read integrationProperties.getXxx().getRunnerUserId().
    return null;
  }

  private String resolveIntegrationRunnerOrgId(String runnerUserId) {
    // TODO(audit E #36 follow-up): look up org for runnerUserId via UserRepository.
    return null;
  }

  // ── Inbound ───────────────────────────────────────────────────────────────

  /**
   * Called by IntegrationController for every inbound webhook. Returns runId or sentinel string.
   */
  public String handleWebhook(String sourceType, Map<String, String> headers, String rawBody) {
    InboundAdapter adapter = inboundAdapters.get(sourceType);
    if (adapter == null)
      throw new IllegalArgumentException("Unknown integration source: " + sourceType);

    adapter.verifySignature(headers, rawBody);

    String eventId = adapter.extractEventId(rawBody);
    if (eventLogRepo.existsBySourceTypeAndEventId(sourceType, eventId)) {
      log.info("[Integration] Duplicate event {} from {} — skipped", eventId, sourceType);
      return "duplicate";
    }

    Optional<IntegrationEvent> maybeEvent = adapter.parseEvent(rawBody);
    if (maybeEvent.isEmpty()) {
      log.info(
          "[Integration] Event {} from {} is ignorable (wrong type/status)", eventId, sourceType);
      saveEventLog(sourceType, eventId);
      return "ignored";
    }

    IntegrationEvent event = maybeEvent.get();
    // Persist idempotency record before submitting to avoid race on webhook retries
    saveEventLog(sourceType, eventId);

    String userRequest = buildUserRequest(event);
    PipelineRun run = submitIntegrationRun(userRequest, sourceType, event.skipApproval());

    IntegrationRun ir =
        IntegrationRun.builder()
            .runId(run.getRunId())
            .sourceType(sourceType)
            .sourceId(event.sourceId())
            .sourceUrl(event.sourceUrl())
            .createdAt(Instant.now())
            .build();
    integrationRunRepo.save(ir);

    log.info(
        "[Integration] Run {} submitted for {} event {}",
        run.getRunId(),
        sourceType,
        event.sourceId());
    return run.getRunId();
  }

  /**
   * Directly submit an event (manual triggers that bypass signature check). Returns the new runId.
   */
  public String submitEvent(IntegrationEvent event) {
    String userRequest = buildUserRequest(event);
    PipelineRun run = submitIntegrationRun(userRequest, event.sourceType(), event.skipApproval());

    IntegrationRun ir =
        IntegrationRun.builder()
            .runId(run.getRunId())
            .sourceType(event.sourceType())
            .sourceId(event.sourceId())
            .sourceUrl(event.sourceUrl())
            .createdAt(Instant.now())
            .build();
    integrationRunRepo.save(ir);

    log.info(
        "[Integration] Manual run {} submitted for {} source {}",
        run.getRunId(),
        event.sourceType(),
        event.sourceId());
    return run.getRunId();
  }

  // ── Query methods ─────────────────────────────────────────────────────────

  /** Returns which source types currently have registered adapters (enabled integrations). */
  public List<String> getEnabledSources() {
    return List.copyOf(inboundAdapters.keySet());
  }

  /** Returns the InboundAdapter for a given source type, if registered. */
  public Optional<InboundAdapter> getInboundAdapter(String sourceType) {
    return Optional.ofNullable(inboundAdapters.get(sourceType));
  }

  /** Returns all integration runs, optionally filtered by source. */
  public List<IntegrationRun> listIntegrationRuns(String source) {
    if (source != null && !source.isBlank()) {
      return integrationRunRepo.findBySourceTypeOrderByCreatedAtDesc(source.toUpperCase());
    }
    return integrationRunRepo.findAllByOrderByCreatedAtDesc();
  }

  // ── Private helpers ───────────────────────────────────────────────────────

  private void saveEventLog(String sourceType, String eventId) {
    IntegrationEventLog entry =
        IntegrationEventLog.builder()
            .sourceType(sourceType)
            .eventId(eventId)
            .createdAt(Instant.now())
            .build();
    eventLogRepo.save(entry);
  }

  /** Per-field length cap when stitching external-issue content into the planner prompt. */
  private static final int FIELD_CAP_CHARS = 4_000;

  private String buildUserRequest(IntegrationEvent event) {
    // Audit 2026-05-12 review item D #31 — Jira / Confluence issue bodies are external,
    // attacker-influenceable content. Sanitize before they hit the planner prompt:
    //  1) Strip prompt-control sequences (lines starting with "SYSTEM:", "===", "<|...|>" etc.)
    //  2) Length-cap every field so a giant issue can't blow the planner's context window.
    StringBuilder sb = new StringBuilder(sanitizeAndCap(event.goal()));
    if (event.contextMarkdown() != null && !event.contextMarkdown().isBlank()) {
      sb.append("\n\n## Additional Context\n").append(sanitizeAndCap(event.contextMarkdown()));
    }
    if (event.acceptanceCriteria() != null && !event.acceptanceCriteria().isBlank()) {
      sb.append("\n\n## Acceptance Criteria\n").append(sanitizeAndCap(event.acceptanceCriteria()));
    }
    return sb.toString();
  }

  /**
   * Strip prompt-injection patterns + cap length. Conservative — only removes lines that look like
   * the LLM-control sequences the planner could mistake for system instructions.
   */
  static String sanitizeAndCap(String raw) {
    if (raw == null) return "";
    String trimmed = raw.length() > FIELD_CAP_CHARS ? raw.substring(0, FIELD_CAP_CHARS) + "…" : raw;
    // Strip obvious injection seeds: "SYSTEM:", "<|im_start|>", "###system", etc.
    // Conservative: only removes the offending TOKENS, leaves surrounding text intact.
    return trimmed
        .replaceAll("(?im)^\\s*(SYSTEM|ASSISTANT|TOOL|USER)\\s*:\\s*$", "")
        .replaceAll("<\\|[^|]{1,32}\\|>", "")
        .replaceAll("(?m)^###\\s*system\\b.*$", "");
  }
}
