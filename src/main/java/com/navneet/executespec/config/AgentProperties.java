package com.navneet.executespec.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent")
@Data
public class AgentProperties {

    private String workspace;
    private Roles roles = new Roles();
    private TokenBudget tokenBudget = new TokenBudget();
    private Compaction compaction = new Compaction();

    @Data
    public static class Roles {
        private String planner = "anthropic";
        private String worker = "anthropic";
        private String reviewer = "anthropic";
    }

    @Data
    public static class TokenBudget {
        /** Must match spring.ai.*.chat.options.max-tokens */
        private int maxTokens = 16000;
        /** Output-token limit for LOW complexity tasks. */
        private int low = 2048;
        /** Output-token limit for MEDIUM complexity tasks. */
        private int medium = 4096;
        /** Output-token limit for HIGH complexity tasks. */
        private int high = 8192;
        /** Absolute max percentage — worker forced to report when exceeded */
        private double hardCeilingPct = 0.90;

        /** Computed hard-ceiling token count. */
        public int getHardCeiling() { return (int) (maxTokens * hardCeilingPct); }
    }

    @Data
    public static class Compaction {
        private double warnPct = 0.65;
        private double compactPct = 0.75;
        private double hardStopPct = 0.90;
    }

    private Concurrency concurrency = new Concurrency();
    private GitHub github = new GitHub();

    @Data
    public static class Concurrency {
        private int maxRuns = 3;
        /** Default agentic loop iterations — used as fallback and for HIGH complexity tasks. */
        private int maxIterations = 80;
        /** Iteration cap for LOW complexity tasks. */
        private int maxIterationsLow = 30;
        /** Iteration cap for MEDIUM complexity tasks. */
        private int maxIterationsMedium = 55;
        /** Iteration cap for HIGH complexity tasks. */
        private int maxIterationsHigh = 80;

        /** % of base cap at which to check progress and potentially extend. */
        private int extensionThresholdPct = 80;
        /** % of base cap to add per extension grant. */
        private int extensionPct = 50;
        /** Absolute ceiling as multiplier of base cap — never exceeded. */
        private int absoluteCeilingMultiplier = 2;

        /** Resolve iteration limit by complexity string (LOW/MEDIUM/HIGH). */
        public int maxIterationsFor(String complexity) {
            if (complexity == null) return maxIterationsMedium;
            return switch (complexity.toUpperCase()) {
                case "LOW" -> maxIterationsLow;
                case "HIGH" -> maxIterationsHigh;
                default -> maxIterationsMedium;
            };
        }
    }

    @Data
    public static class GitHub {
        private String pat = "";
        private String defaultOwner = "";
    }

    private Shell shell = new Shell();

    @Data
    public static class Shell {
        private boolean sandboxEnabled = true;
        private int timeoutSeconds = 300;
        private java.util.List<String> additionalAllowedCommands = new java.util.ArrayList<>();
        private java.util.List<String> additionalBlockedPatterns = new java.util.ArrayList<>();
    }

    // ── Tier 8: Compile agent ─────────────────────────────────────────────────
    private Compiler compiler = new Compiler();

    @Data
    public static class Compiler {
        private boolean enabled      = true;
        private int maxAttempts      = 3;
        private int timeoutSeconds   = 300;
        private String model         = "claude-haiku-4-5-20251001";
        private String provider      = "anthropic";
    }

    // ── Tier 8: API test suite agent ─────────────────────────────────────────
    private ApiTestSuite apiTestSuite = new ApiTestSuite();

    @Data
    public static class ApiTestSuite {
        private boolean enabled = true;
        private String model    = "claude-haiku-4-5-20251001";
        private String provider = "anthropic";
    }

    // ── Tier 8: Frontend preview ──────────────────────────────────────────────
    private Preview preview = new Preview();

    @Data
    public static class Preview {
        private boolean enabled = true;
        private int startupTimeoutSeconds = 120;
        private int logBufferLines = 200;
    }
}
