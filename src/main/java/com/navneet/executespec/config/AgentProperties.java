package com.navneet.executespec.config;

// Model defaults below are string literals matching application.yml canonical names.
// YAML values override these at runtime via @ConfigurationProperties binding.

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

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
    public int getHardCeiling() {
      return (int) (maxTokens * hardCeilingPct);
    }
  }

  @Data
  public static class Compaction {
    private double warnPct = 0.65;
    private double compactPct = 0.75;
    private double hardStopPct = 0.90;
  }

  private Cost cost = new Cost();
  private Concurrency concurrency = new Concurrency();
  private GitHub github = new GitHub();

  @Data
  public static class Cost {
    /** USD budget per run credit. */
    private double creditMultiplierUsd = 5.0;

    /** Buffer multiplier on cost estimate (1.10 = 10% for retry overhead). */
    private double estimationBuffer = 1.10;

    /** Safety ceiling multiplier: hard cap = credits × multiplier × margin. */
    private double budgetMargin = 1.5;

    /** Maximum credits a FREE-tier user can consume per run. */
    private int maxCreditsFree = 1;

    /** Batch execution config. */
    private Batch batch = new Batch();
  }

  @Data
  public static class Batch {
    private boolean enabled = true;

    /** Polling interval for pending batch runs (seconds). */
    private int pollIntervalSeconds = 30;

    /** Max hours to wait for a batch to complete before timing out. */
    private int maxWaitHours = 4;

    /** Universal batch discount (0.50 = 50% off). */
    private double discount = 0.50;

    /** Batch tasks get this multiplier on max_output_tokens. */
    private double maxOutputTokenMultiplier = 1.5;

    /** Google batch: use inline requests for tasks below this limit, JSONL above. */
    private int googleInlineTaskLimit = 10;
  }

  @Data
  public static class Concurrency {
    private int maxRuns = 3;

    /** Maximum wall-clock time per task in minutes. Replaces iteration-based caps. */
    private int maxTaskDurationMinutes = 15;

    /** Safety-net iteration cap to prevent infinite loops (should never trigger). */
    private int safetyIterationCap = 500;

    /** Number of automatic retries on task/subtask failure (0 = no retry). */
    private int taskMaxRetries = 2;
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

    /**
     * Directories prepended to PATH in worker shell processes (e.g.
     * "/opt/homebrew/bin:/usr/local/bin").
     */
    private String pathPrepend = "";

    private java.util.List<String> additionalAllowedCommands = new java.util.ArrayList<>();
    private java.util.List<String> additionalBlockedPatterns = new java.util.ArrayList<>();
  }

  // ── Tier 8: Compile agent ─────────────────────────────────────────────────
  private Compiler compiler = new Compiler();

  @Data
  public static class Compiler {
    private boolean enabled = true;
    private int maxAttempts = 3;
    private int timeoutSeconds = 300;
    private String model = "claude-haiku-4-5-20251001";
    private String provider = "anthropic";
  }

  // ── Tier 8: API test suite agent ─────────────────────────────────────────
  private ApiTestSuite apiTestSuite = new ApiTestSuite();

  @Data
  public static class ApiTestSuite {
    private boolean enabled = true;
    private String model = "claude-haiku-4-5-20251001";
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
