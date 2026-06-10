package com.navneet.executespec.agent;

/**
 * Resolved API keys for a V2 run, scoped per provider.
 *
 * <p>Built once by {@code RunOrchestrator.runPipeline} from {@link
 * com.navneet.executespec.agent.ApiKeyResolver#resolve(String)} and stashed in {@link
 * RunKeyContextStore} for the run's lifetime. {@link LlmRouter#call} reads it back via {@code
 * request.context().runId()} and threads the appropriate provider key into the dispatch path so the
 * V2 pipeline actually uses customer-configured BYOK keys instead of platform singletons.
 *
 * <p>Each key field may be {@code null} — that signals "no user/org key configured for this
 * provider, fall back to platform default". The {@link Source} flags travel alongside so {@code
 * pipeline_run.key_source} + the {@code executespec.v2.llm.key_source} metric can attribute every
 * dispatch to its origin.
 *
 * <p>Audit context (Sprint 4 BYOK deep-dive, 2026-05-01): without this record + the
 * RunKeyContextStore, the V2 router silently dispatched every paying customer's run against the
 * platform's {@code spring.ai.*.api-key} env vars — see {@code audit-output/BYOK_DEEPDIVE.md} for
 * the full chain-of-evidence.
 */
public record RunKeyContext(
    String anthropicKey,
    String openAiKey,
    String geminiKey,
    String openRouterKey,
    String deepSeekKey,
    String fireworksKey,
    Source anthropicSource,
    Source openAiSource,
    Source geminiSource,
    Source openRouterSource,
    Source deepSeekSource,
    Source fireworksSource,
    String userId,
    String orgId) {

  public RunKeyContext(
      String anthropicKey,
      String openAiKey,
      String geminiKey,
      String openRouterKey,
      Source anthropicSource,
      Source openAiSource,
      Source geminiSource,
      Source openRouterSource,
      String userId,
      String orgId) {
    this(
        anthropicKey,
        openAiKey,
        geminiKey,
        openRouterKey,
        null,
        null,
        anthropicSource,
        openAiSource,
        geminiSource,
        openRouterSource,
        Source.PLATFORM,
        Source.PLATFORM,
        userId,
        orgId);
  }

  public enum Source {
    /** User-level key (app_user.{provider}_api_key). */
    USER,
    /** Org-level key (org.{provider}_api_key). */
    ORG,
    /** No customer-configured key — falls through to {@code spring.ai.*.api-key}. */
    PLATFORM
  }

  /**
   * Returns the resolved key for {@code provider}, or {@code null} when the customer has not
   * configured one (router falls back to platform default).
   */
  public String keyFor(String provider) {
    if (provider == null) return null;
    return switch (provider.toLowerCase()) {
      case "anthropic" -> anthropicKey;
      case "openai" -> openAiKey;
      case "gemini" -> geminiKey;
      case "openrouter" -> openRouterKey;
      case "deepseek" -> deepSeekKey;
      case "fireworks" -> fireworksKey;
      default -> null;
    };
  }

  /** Source label for {@code provider}, defaults to PLATFORM on unknown. */
  public Source sourceFor(String provider) {
    if (provider == null) return Source.PLATFORM;
    return switch (provider.toLowerCase()) {
      case "anthropic" -> anthropicSource != null ? anthropicSource : Source.PLATFORM;
      case "openai" -> openAiSource != null ? openAiSource : Source.PLATFORM;
      case "gemini" -> geminiSource != null ? geminiSource : Source.PLATFORM;
      case "openrouter" -> openRouterSource != null ? openRouterSource : Source.PLATFORM;
      case "deepseek" -> deepSeekSource != null ? deepSeekSource : Source.PLATFORM;
      case "fireworks" -> fireworksSource != null ? fireworksSource : Source.PLATFORM;
      default -> Source.PLATFORM;
    };
  }

  /**
   * The dominant key source for the run — used to stamp {@code pipeline_run.key_source} so
   * historical audits can answer "which keys handled this run?". Picks the first non-PLATFORM
   * source among the three providers, defaulting to PLATFORM when every provider falls back. Ties
   * (e.g. user has Anthropic + Org has OpenAI) are resolved in provider order — Anthropic wins
   * because it's the most-used provider in production today.
   */
  public Source dominantSource() {
    if (anthropicSource != null && anthropicSource != Source.PLATFORM) return anthropicSource;
    if (openAiSource != null && openAiSource != Source.PLATFORM) return openAiSource;
    if (geminiSource != null && geminiSource != Source.PLATFORM) return geminiSource;
    if (openRouterSource != null && openRouterSource != Source.PLATFORM) return openRouterSource;
    if (deepSeekSource != null && deepSeekSource != Source.PLATFORM) return deepSeekSource;
    if (fireworksSource != null && fireworksSource != Source.PLATFORM) return fireworksSource;
    return Source.PLATFORM;
  }
}
