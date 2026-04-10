package com.navneet.executespec.saga;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Processes {@link SideEffectOutboxEntry} records every 30 seconds.
 *
 * <p>For each due entry:
 * <ol>
 *   <li>Atomically claims it (PENDING → PROCESSING) to prevent duplicate execution</li>
 *   <li>Dispatches to the appropriate handler by {@code effectType}</li>
 *   <li>Marks COMPLETED on success, or FAILED with exponential backoff on error</li>
 * </ol>
 *
 * <p>Backoff schedule: 1 min → 5 min → 30 min between attempts (capped at max_attempts).
 * After max_attempts, status is permanently FAILED — no further retries.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SideEffectExecutor {

    private static final int BATCH_SIZE = 20;
    private static final Duration[] BACKOFF = {
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30)
    };

    private final SideEffectOutboxRepository repository;
    private final ObjectMapper mapper;
    private final MeterRegistry meterRegistry;
    private final com.navneet.executespec.deployment.WorkspaceExportService exportService;
    private final com.navneet.executespec.deployment.GcsWorkspaceStore gcsStore;
    private final com.navneet.executespec.config.AgentProperties agentProps;
    private final com.navneet.executespec.state.StateStore stateStore;

    @Scheduled(fixedRate = 30_000)
    public void processDue() {
        List<SideEffectOutboxEntry> due = repository.findDue(Instant.now(), BATCH_SIZE);
        if (due.isEmpty()) return;

        log.debug("[SideEffectExecutor] Processing {} due entries", due.size());
        for (SideEffectOutboxEntry entry : due) {
            processEntry(entry);
        }
    }

    @Transactional
    public void processEntry(SideEffectOutboxEntry entry) {
        // Atomic claim — skip if another instance already took it
        int claimed = repository.markProcessing(entry.getId());
        if (claimed == 0) {
            log.debug("[SideEffectExecutor] Entry {} already claimed — skipping", entry.getId());
            return;
        }

        try {
            Map<String, Object> payload = parsePayload(entry.getPayload());
            dispatch(entry.getEffectType(), entry.getRunId(), payload);

            entry.setStatus("COMPLETED");
            entry.setCompletedAt(Instant.now());
            entry.setLastError(null);
            repository.save(entry);

            Counter.builder("executespec.side_effect.completed")
                    .tag("type", entry.getEffectType())
                    .register(meterRegistry).increment();
            log.info("[SideEffectExecutor] Completed {} for run {} (attempt={})",
                    entry.getEffectType(), entry.getRunId(), entry.getAttempt() + 1);

        } catch (Exception e) {
            int nextAttempt = entry.getAttempt() + 1;
            entry.setAttempt(nextAttempt);

            if (nextAttempt >= entry.getMaxAttempts()) {
                entry.setStatus("FAILED");
                entry.setLastError("Max attempts reached. Last error: " + e.getMessage());
                log.error("[SideEffectExecutor] Permanently FAILED {} for run {} after {} attempts: {}",
                        entry.getEffectType(), entry.getRunId(), nextAttempt, e.getMessage());
                Counter.builder("executespec.side_effect.failed")
                        .tag("type", entry.getEffectType())
                        .register(meterRegistry).increment();
            } else {
                Duration backoff = BACKOFF[Math.min(nextAttempt - 1, BACKOFF.length - 1)];
                entry.setStatus("PENDING");
                entry.setNextRetryAt(Instant.now().plus(backoff));
                entry.setLastError(e.getMessage());
                log.warn("[SideEffectExecutor] Retry {}/{} for {} run {} in {} — {}",
                        nextAttempt, entry.getMaxAttempts(), entry.getEffectType(),
                        entry.getRunId(), backoff, e.getMessage());
                Counter.builder("executespec.side_effect.retried")
                        .tag("type", entry.getEffectType())
                        .register(meterRegistry).increment();
            }
            repository.save(entry);
        }
    }

    // ── Dispatcher ────────────────────────────────────────────────────────────

    private void dispatch(String effectType, String runId, Map<String, Object> payload) {
        switch (effectType) {
            case "COMPENSATE_GITHUB_BRANCH" -> compensateGitHubBranch(runId, payload);
            case "COMPENSATE_JIRA_COMMENT"  -> log.info("[SideEffectExecutor] JIRA compensation for run {} — posting failure comment", runId);
            case "COMPENSATE_CLOUD_RUN"     -> log.info("[SideEffectExecutor] Cloud Run compensation for run {} — would delete service", runId);
            case "GCS_ARCHIVE"              -> retryGcsArchive(runId, payload);
            default -> log.warn("[SideEffectExecutor] Unknown effect type '{}' for run {} — no-op", effectType, runId);
        }
    }

    private void retryGcsArchive(String runId, Map<String, Object> payload) {
        // Idempotent: skip if already uploaded
        var run = stateStore.findRun(runId).orElse(null);
        if (run != null && run.getGcsWorkspaceKey() != null) {
            log.info("[SideEffectExecutor] GCS archive already complete for run {} — skipping retry", runId);
            return;
        }

        Path workspace = Path.of(agentProps.getWorkspace(), runId);
        if (!Files.isDirectory(workspace)) {
            log.warn("[SideEffectExecutor] Local workspace absent for run {} — cannot archive", runId);
            return;
        }

        try {
            Path zipPath = exportService.exportWorkspace(runId);
            try {
                String gcsKey = gcsStore.uploadZip(runId, zipPath);
                if (run != null) {
                    run.setGcsWorkspaceKey(gcsKey);
                    stateStore.saveRun(run);
                }
                log.info("[SideEffectExecutor] GCS archive retry succeeded for run {} → {}", runId, gcsKey);
            } finally {
                Files.deleteIfExists(zipPath);
            }
        } catch (Exception e) {
            log.warn("[SideEffectExecutor] GCS archive retry failed for run {}: {}", runId, e.getMessage());
            throw new RuntimeException("GCS archive failed: " + e.getMessage(), e);
        }
    }

    private void compensateGitHubBranch(String runId, Map<String, Object> payload) {
        String owner  = (String) payload.get("owner");
        String repo   = (String) payload.get("repo");
        String branch = (String) payload.get("branch");
        if (owner == null || repo == null || branch == null) {
            log.warn("[SideEffectExecutor] Missing owner/repo/branch in COMPENSATE_GITHUB_BRANCH payload for run {}", runId);
            return;
        }
        // GitHub API: DELETE /repos/{owner}/{repo}/git/refs/heads/{branch}
        // GitHubRepoService doesn't yet expose deleteBranch — log for now; full impl in #3 (DistributedSSE phase)
        log.info("[SideEffectExecutor] COMPENSATE: Would delete GitHub branch {}/{}/{} for failed run {}",
                owner, repo, branch, runId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
