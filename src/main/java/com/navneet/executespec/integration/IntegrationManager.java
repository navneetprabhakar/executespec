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
import com.navneet.executespec.v2.integration.V2RunOrchestrator;

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
  private final V2RunOrchestrator v2Orchestrator;

  public IntegrationManager(
      List<InboundAdapter> inbound,
      IntegrationRunRepository integrationRunRepo,
      IntegrationEventLogRepository eventLogRepo,
      StateStore stateStore,
      V2RunOrchestrator v2Orchestrator) {
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
  private PipelineRun submitIntegrationRun(
      String userRequest, String sourceType, boolean skipApproval) {
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
        /* userId */ null,
        /* orgId */ null,
        /* projectId */ null,
        "PROTOTYPE");
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

  private String buildUserRequest(IntegrationEvent event) {
    StringBuilder sb = new StringBuilder(event.goal());
    if (event.contextMarkdown() != null && !event.contextMarkdown().isBlank()) {
      sb.append("\n\n## Additional Context\n").append(event.contextMarkdown());
    }
    if (event.acceptanceCriteria() != null && !event.acceptanceCriteria().isBlank()) {
      sb.append("\n\n## Acceptance Criteria\n").append(event.acceptanceCriteria());
    }
    return sb.toString();
  }
}
