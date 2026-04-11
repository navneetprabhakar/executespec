package com.navneet.executespec.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.api.RunMapper;
import com.navneet.executespec.api.dto.RunDetail;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;

/**
 * Pushes full {@link RunDetail} payloads via SSE whenever key run state
 * changes occur, eliminating the need for frontend API polling.
 *
 * <p>Listens for {@link RunEvent} Spring events and, for state-changing event types
 * (TASK_STATUS, RUN_STATUS, GATE_OPENED, COMPLETE, etc.), emits a {@code RUN_DETAIL}
 * SSE event with the complete run detail JSON payload.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RunDetailSseService {

    private static final Set<String> DETAIL_TRIGGER_TYPES = Set.of(
            "TASK_STATUS", "RUN_STATUS", "GATE_OPENED", "GATE_RESOLVED",
            "COMPLETE", "RUN_RESTARTED", "PREVIEW_READY", "REVIEW_COMPLETE",
            "TEST_REPORT_READY", "VALIDATION_REPORT_READY", "BUDGET_EXCEEDED", "TASK_DECOMPOSED"
    );

    private final SseBroadcaster broadcaster;
    private final StateStore stateStore;
    private final RunMapper runMapper;
    private final ObjectMapper objectMapper;

    /**
     * Reacts to RunEvent Spring events emitted by SseBroadcaster.
     * For state-changing events, fetches the full RunDetail and pushes it via SSE.
     */
    @Async
    @EventListener
    public void onRunEvent(RunEvent event) {
        if (!DETAIL_TRIGGER_TYPES.contains(event.type())) {
            return;
        }

        String runId = event.runId();
        try {
            PipelineRun run = stateStore.findRun(runId).orElse(null);
            if (run == null) return;

            RunDetail detail = runMapper.toDetail(run, stateStore.getAllTasks(runId));
            String detailJson = objectMapper.writeValueAsString(detail);

            // Emit as a special RUN_DETAIL event — payload is the full RunDetail JSON
            RunEvent detailEvent = new RunEvent("RUN_DETAIL", runId, null, detailJson, Instant.now());
            broadcaster.emitDirect(runId, detailEvent);
        } catch (Exception e) {
            // Non-fatal — worst case the frontend falls back to its safety-net poll
            log.debug("Failed to push RUN_DETAIL for run {}: {}", runId, e.getMessage());
        }
    }
}
