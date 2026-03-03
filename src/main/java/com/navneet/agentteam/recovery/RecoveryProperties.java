package com.navneet.agentteam.recovery;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "agent.recovery")
@Data
public class RecoveryProperties {

    /** Master switch — set false to disable all recovery logic */
    private boolean enabled = true;

    /** Minutes of inactivity before a warning is emitted to SSE */
    private int warnThresholdMinutes = 5;

    /** Minutes of inactivity before evaluation and recovery are triggered */
    private int actThresholdMinutes = 10;

    /** Max retries per task before escalating to full run restart */
    private int maxTaskRetries = 1;

    /** How many recent log entries the evaluator inspects */
    private int evaluatorLogWindow = 20;

    /** How many times the same tool must appear in the log window to be a loop */
    private int loopRepeatThreshold = 3;

    /** When true, auto-restarts go through ApprovalGate before fresh run starts */
    private boolean requireRestartApproval = false;

    // ── Transient retry config ────────────────────────────────────────────────
    private int maxTransientRetries = 3;
    private int rateLimitBackoffSeconds = 30;
    private int serverErrorBackoffSeconds = 10;

    // ── Auto-restart on transient run failure ─────────────────────────────────
    /** Max times a run is allowed to auto-restart due to transient provider failures. */
    private int maxAutoRestarts = 3;
    /** Seconds to wait before spawning the replacement run (gives rate-limits time to clear). */
    private int autoRestartBackoffSeconds = 60;

    // ── Escalation ladder ─────────────────────────────────────────────────────
    private Map<String, String> capableModels = new HashMap<>(Map.of(
        "anthropic", "claude-haiku-4-5-20251001",
        "openai",    "gpt-4o"
    ));

    private Map<String, String> alternateProvider = new HashMap<>(Map.of(
        "anthropic", "openai",
        "openai",    "anthropic"
    ));

    private String defaultProvider = "anthropic";

    // ── Convenience lookups (not simple getters — kept as methods) ─────────────

    public String getCapableModelFor(String provider) {
        return capableModels.get(provider != null ? provider.toLowerCase() : "anthropic");
    }

    public String getAlternateProviderFor(String provider) {
        return alternateProvider.get(provider != null ? provider.toLowerCase() : "anthropic");
    }
}
