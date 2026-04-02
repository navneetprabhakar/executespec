package com.navneet.executespec.integration;

import com.navneet.executespec.harness.PipelineHarness;
import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.reviewer.model.ReviewReportV2;
import com.navneet.executespec.state.entity.IntegrationEventLog;
import com.navneet.executespec.state.entity.IntegrationRun;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.IntegrationEventLogRepository;
import com.navneet.executespec.state.repository.IntegrationRunRepository;
import io.micrometer.context.ContextSnapshotFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.stream.Collectors.toMap;

/**
 * Central hub for all integration traffic.
 * Routes inbound webhooks to the pipeline and writes status changes back to source systems.
 * The pipeline (PlannerAgent, WorkerAgent, ReviewerAgent) is completely unaware this exists.
 *
 * <p>Outbound notifications listen for {@link PipelineEvent} via Spring's event bus —
 * no direct circular dependency with {@link PipelineHarness}.
 * Inbound submission injects {@link PipelineHarness} directly (safe, no cycle).
 */
@Service
@Slf4j
public class IntegrationManager {

    private final Map<String, InboundAdapter> inboundAdapters;
    private final Map<String, OutboundAdapter> outboundAdapters;
    private final IntegrationRunRepository integrationRunRepo;
    private final IntegrationEventLogRepository eventLogRepo;
    private final StateStore stateStore;
    private final PipelineHarness harness;

    public IntegrationManager(
            List<InboundAdapter> inbound,
            List<OutboundAdapter> outbound,
            IntegrationRunRepository integrationRunRepo,
            IntegrationEventLogRepository eventLogRepo,
            StateStore stateStore,
            PipelineHarness harness) {
        this.inboundAdapters  = inbound.stream().collect(toMap(InboundAdapter::sourceType, a -> a));
        this.outboundAdapters = outbound.stream().collect(toMap(OutboundAdapter::sourceType, a -> a));
        this.integrationRunRepo = integrationRunRepo;
        this.eventLogRepo = eventLogRepo;
        this.stateStore = stateStore;
        this.harness = harness;
    }

    // ── Inbound ───────────────────────────────────────────────────────────────

    /** Called by IntegrationController for every inbound webhook. Returns runId or sentinel string. */
    public String handleWebhook(String sourceType, Map<String, String> headers, String rawBody) {
        InboundAdapter adapter = inboundAdapters.get(sourceType);
        if (adapter == null) throw new IllegalArgumentException("Unknown integration source: " + sourceType);

        adapter.verifySignature(headers, rawBody);

        String eventId = adapter.extractEventId(rawBody);
        if (eventLogRepo.existsBySourceTypeAndEventId(sourceType, eventId)) {
            log.info("[Integration] Duplicate event {} from {} — skipped", eventId, sourceType);
            return "duplicate";
        }

        Optional<IntegrationEvent> maybeEvent = adapter.parseEvent(rawBody);
        if (maybeEvent.isEmpty()) {
            log.info("[Integration] Event {} from {} is ignorable (wrong type/status)", eventId, sourceType);
            saveEventLog(sourceType, eventId);
            return "ignored";
        }

        IntegrationEvent event = maybeEvent.get();
        // Persist idempotency record before submitting to avoid race on webhook retries
        saveEventLog(sourceType, eventId);

        String userRequest = buildUserRequest(event);
        PipelineRun run = harness.submitIntegrationRun(userRequest, sourceType, event.skipApproval());

        IntegrationRun ir = IntegrationRun.builder()
                .runId(run.getRunId())
                .sourceType(sourceType)
                .sourceId(event.sourceId())
                .sourceUrl(event.sourceUrl())
                .createdAt(Instant.now())
                .build();
        integrationRunRepo.save(ir);

        log.info("[Integration] Run {} submitted for {} event {}", run.getRunId(), sourceType, event.sourceId());
        return run.getRunId();
    }

    /**
     * Directly submit an event (manual triggers that bypass signature check).
     * Returns the new runId.
     */
    public String submitEvent(IntegrationEvent event) {
        String userRequest = buildUserRequest(event);
        PipelineRun run = harness.submitIntegrationRun(userRequest, event.sourceType(), event.skipApproval());

        IntegrationRun ir = IntegrationRun.builder()
                .runId(run.getRunId())
                .sourceType(event.sourceType())
                .sourceId(event.sourceId())
                .sourceUrl(event.sourceUrl())
                .createdAt(Instant.now())
                .build();
        integrationRunRepo.save(ir);

        log.info("[Integration] Manual run {} submitted for {} source {}", run.getRunId(), event.sourceType(), event.sourceId());
        return run.getRunId();
    }

    // ── Outbound — Spring event listeners ────────────────────────────────────

    @EventListener
    public void onRunStarted(PipelineEvent.RunStarted event) {
        notifyOutbound(event.runId(), "PLANNING");
    }

    /**
     * Listen to MergeCompleted instead of RunCompleted — the review report is guaranteed
     * to exist at this point. RunCompleted fires before background review starts, so the
     * review report is null and adapters silently skip the notification.
     */
    @EventListener
    public void onMergeCompleted(PipelineEvent.MergeCompleted event) {
        notifyOutbound(event.runId(), "COMPLETE");
    }

    @EventListener
    public void onRunFailed(PipelineEvent.RunFailed event) {
        notifyOutbound(event.runId(), "FAILED");
    }

    /**
     * Runs the outbound integration callback in a virtual thread — never blocks the caller.
     */
    private void notifyOutbound(String runId, String newStatus) {
        Runnable task = ContextSnapshotFactory.builder().build().captureAll().wrap(() -> {
            integrationRunRepo.findByRunId(runId).ifPresent(ir -> {
                OutboundAdapter adapter = outboundAdapters.get(ir.getSourceType());
                if (adapter == null) return;

                PipelineRun run;
                try {
                    run = stateStore.findRun(runId).orElse(null);
                    if (run == null) return;
                } catch (Exception e) {
                    log.error("[Integration] Could not load run {} for outbound callback: {}", runId, e.getMessage());
                    return;
                }

                try {
                    switch (newStatus) {
                        case "PLANNING" -> adapter.onRunStarted(ir, run);
                        case "COMPLETE" -> {
                            ReviewReportV2 review = stateStore.getReviewReportV2(runId);
                            adapter.onRunComplete(ir, run, review);
                        }
                        case "FAILED" -> adapter.onRunFailed(ir, run,
                                run.getFailureReason() != null ? run.getFailureReason() : "Unknown failure");
                        default -> {}
                    }
                } catch (Exception e) {
                    log.error("[Integration] Outbound call failed for run {} to {}: {}",
                            runId, ir.getSourceType(), e.getMessage());
                }
            });
        });
        Thread.ofVirtual().name("integration-outbound-" + runId).start(task);
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
        IntegrationEventLog entry = IntegrationEventLog.builder()
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
