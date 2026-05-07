package com.navneet.executespec.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Phase 0 of the hierarchical planner — chooses the planning strategy for a run before any
 * expensive LLM planner call fires.
 *
 * <p>Logic:
 *
 * <ol>
 *   <li><b>Heuristic fast path</b> — obvious tiny specs (≤cap chars, 1 repo, no multi-feature
 *       keywords, no domain-explosive verbs) short-circuit to {@link PlanStrategy#SINGLE_SHOT} with
 *       no LLM call.
 *   <li><b>LLM verifier</b> — everything else hits a LIGHTWEIGHT-tier LLM with the heuristic
 *       verdict as a hint. The LLM returns {@link ClassificationResult} as JSON.
 *   <li><b>Redis cache</b> — keyed by {@code SHA-256(spec + sorted-repos + projectId)}, 1h TTL.
 *       Resubmissions in the window reuse the verdict.
 *   <li><b>Fallback</b> — LLM timeout / parse error / enum mismatch returns the heuristic verdict
 *       instead of failing the run. Classifier outage must never block planning.
 * </ol>
 *
 * <p>Cost (live): {@code ~$0.001-0.003} per LLM call on Haiku/Flash. Heuristic fast path: {@code
 * 0ms / $0}. See {@code docs/hierarchical-planner-plan.md}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SmartClassifierAgent {

  private final LlmRouter router;
  private final ModelResolver modelResolver;
  private final StringRedisTemplate redis;
  private final PromptAssembler promptAssembler;
  private final ObjectMapper objectMapper;
  private final com.navneet.executespec.observability.Metrics metrics;

  /** Char cap for the heuristic fast path. Longer specs always hit the LLM verifier. */
  private static final int HEURISTIC_FAST_PATH_CHAR_CAP = 150;

  /** LLM verifier max tokens — JSON output should easily fit in 500. */
  private static final int LLM_MAX_TOKENS = 500;

  /** Redis cache TTL for the {spec,repos,projectId} → ClassificationResult mapping. */
  private static final Duration CACHE_TTL = Duration.ofHours(1);

  /** Redis key prefix. */
  private static final String CACHE_PREFIX = "planner:classify:";

  /**
   * Words that, when present in the spec, force the heuristic to defer to the LLM verifier even
   * when the spec is short. These are domain-loaded — "auth" in 4 words can mean anything from "fix
   * the login button text" to "rebuild the auth layer", so we don't trust the heuristic alone.
   */
  private static final Set<String> DOMAIN_EXPLOSIVE =
      Set.of(
          "auth",
          "authentication",
          "authorization",
          "stripe",
          "razorpay",
          "payment",
          "billing",
          "rbac",
          "multi-tenant",
          "multitenancy",
          "tenant",
          "real-time",
          "realtime",
          "websocket",
          "migrate",
          "migration",
          "refactor",
          "rebuild",
          "rewrite",
          "internationali",
          "i18n",
          "search",
          "indexing",
          "analytics",
          "tracking",
          "observability",
          "metrics",
          "tracing");

  /**
   * Phrases that strongly imply multi-feature scope. Even one match disqualifies the heuristic fast
   * path.
   */
  private static final Set<String> MULTI_FEATURE_PHRASES =
      Set.of(
          " and also ",
          " plus ",
          " across ",
          " for each ",
          " all repos ",
          " system-wide ",
          " everywhere ",
          " every service ",
          " each repo ");

  /**
   * [Q-NEW-04] (audit Sprint 4 follow-up, 2026-05-01) — structural signal for HTTP API specs:
   * counts lines that look like {@code "POST /todos"} / {@code "- GET /users/:id"} / {@code "*
   * DELETE /v1/orders/:id"}. 4+ endpoints in a single spec is a reliable "this is at least MEDIUM"
   * signal even when the total length is below the SMALL/MEDIUM 800-char boundary. Closes the audit
   * gap where a 698-char 5-endpoint Express spec was bucketed as SMALL on length alone.
   */
  private static final Pattern ENDPOINT_LINE =
      Pattern.compile(
          "(?m)^\\s*[-*]?\\s*(?:POST|GET|PUT|DELETE|PATCH)\\s+/\\S+", Pattern.CASE_INSENSITIVE);

  private static int countEndpoints(String spec) {
    if (spec == null) return 0;
    Matcher m = ENDPOINT_LINE.matcher(spec);
    int n = 0;
    while (m.find()) n++;
    return n;
  }

  public ClassificationResult classify(
      String spec,
      List<String> repoFullNames,
      String projectId,
      String projectType,
      LlmProvider aiProvider,
      String projectMemorySnippet) {
    // ── Cache lookup ─────────────────────────────────────────────────────
    String cacheKey = cacheKey(spec, repoFullNames, projectId);
    if (cacheKey != null) {
      try {
        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null && !cached.isBlank()) {
          ClassificationResult fromCache = parseCached(cached);
          if (fromCache != null) {
            log.info(
                "SmartClassifier cache hit project={} key={} → {}",
                projectId,
                cacheKey,
                fromCache.strategy());
            return fromCache;
          }
        }
      } catch (Exception e) {
        // Cache failure must never block classification.
        log.debug("SmartClassifier redis lookup failed: {}", e.getMessage());
      }
    }

    int repoCount = repoFullNames == null ? 1 : Math.max(1, repoFullNames.size());

    // ── Heuristic ───────────────────────────────────────────────────────
    HeuristicVerdict heuristic = runHeuristic(spec, repoCount);

    // Heuristic fast path — only when the heuristic is confidently SINGLE_SHOT.
    if (heuristic.fastPath) {
      ClassificationResult result =
          new ClassificationResult(
              heuristic.strategy,
              1,
              1,
              false,
              heuristic.reasoning,
              ClassificationResult.Source.HEURISTIC_FAST_PATH,
              0L,
              0L,
              0.0);
      cachePut(cacheKey, result);
      log.info(
          "SmartClassifier heuristic-fast-path → {} ({})", result.strategy(), result.reasoning());
      return result;
    }

    // ── LLM verifier ────────────────────────────────────────────────────
    ClassificationResult verified =
        invokeLlmVerifier(
            spec,
            repoFullNames,
            projectType,
            projectMemorySnippet,
            heuristic,
            aiProvider,
            projectId);
    cachePut(cacheKey, verified);
    return verified;
  }

  // ──────────────────────────────────────────────────────────────────────
  // Heuristic
  // ──────────────────────────────────────────────────────────────────────

  private record HeuristicVerdict(PlanStrategy strategy, boolean fastPath, String reasoning) {}

  private HeuristicVerdict runHeuristic(String spec, int repoCount) {
    if (spec == null || spec.isBlank()) {
      return new HeuristicVerdict(
          PlanStrategy.SINGLE_SHOT, true, "empty spec → defer to single-shot synthesised plan");
    }
    String trimmed = spec.trim();
    int len = trimmed.length();
    String lower = trimmed.toLowerCase(Locale.ROOT);

    // Multi-feature phrase check — disqualifies fast path regardless of length.
    boolean hasMultiFeature = MULTI_FEATURE_PHRASES.stream().anyMatch(p -> lower.contains(p));
    // Domain-loaded keyword — short specs with these are almost always
    // larger than they look.
    boolean hasDomainExplosive = DOMAIN_EXPLOSIVE.stream().anyMatch(k -> lower.contains(k));

    // Strong upgrade signals — multi-repo or refactor verbs always go to
    // LARGE/LEGACY before the LLM has a say.
    if (repoCount >= 3
        || lower.contains("migrate ")
        || lower.contains("refactor ")
        || lower.contains("rewrite ")
        || lower.contains("system-wide")) {
      return new HeuristicVerdict(
          PlanStrategy.LARGE,
          false,
          "multi-repo or migration/refactor scope → LARGE (LLM may upgrade to LEGACY)");
    }
    if (repoCount == 2) {
      return new HeuristicVerdict(PlanStrategy.MEDIUM, false, "2 repos in scope → at least MEDIUM");
    }

    // [Q-NEW-04] Endpoint-count override — 4+ HTTP endpoints means
    // multi-feature scope regardless of total length, since each
    // endpoint typically expands to ≥1 task. Checked BEFORE the
    // SINGLE_SHOT fast-path so a tight 4-endpoint API spec doesn't
    // get misclassified as a one-shot just because it's short.
    // Capped at ≤2000 chars so >2000-char specs (where length carries
    // a lot of supporting context) still flow through the LARGE bucket.
    int endpoints = countEndpoints(trimmed);
    if (endpoints >= 4 && len <= 2000) {
      return new HeuristicVerdict(
          PlanStrategy.MEDIUM, false, endpoints + " HTTP endpoints → MEDIUM (multi-feature scope)");
    }

    // Length-driven defaults for single-repo specs.
    if (len <= HEURISTIC_FAST_PATH_CHAR_CAP && !hasMultiFeature && !hasDomainExplosive) {
      return new HeuristicVerdict(
          PlanStrategy.SINGLE_SHOT,
          true,
          "≤" + HEURISTIC_FAST_PATH_CHAR_CAP + " chars, 1 repo, no expansive keywords");
    }
    if (len <= 800) {
      return new HeuristicVerdict(
          PlanStrategy.SMALL,
          false,
          "≤800 chars, single feature scope (LLM may downgrade or upgrade)");
    }
    if (len <= 2000) {
      return new HeuristicVerdict(PlanStrategy.MEDIUM, false, "≤2000 chars, single repo");
    }
    return new HeuristicVerdict(
        PlanStrategy.LARGE, false, ">2000 chars → at least LARGE (LLM may upgrade to LEGACY)");
  }

  // ──────────────────────────────────────────────────────────────────────
  // LLM verifier
  // ──────────────────────────────────────────────────────────────────────

  private ClassificationResult invokeLlmVerifier(
      String spec,
      List<String> repoFullNames,
      String projectType,
      String projectMemorySnippet,
      HeuristicVerdict heuristic,
      LlmProvider aiProvider,
      String projectId) {
    ResolvedModel resolved;
    try {
      resolved =
          modelResolver.resolve(
              "SmartClassifierAgent",
              aiProvider == null ? LlmProvider.OPENAI : aiProvider,
              AgentTier.LIGHTWEIGHT);
    } catch (Exception e) {
      log.warn(
          "SmartClassifier model resolve failed: {} — using heuristic verdict", e.getMessage());
      metrics.recordClassifierVerifier("SKIPPED_MODEL_RESOLVE");
      return fallback(heuristic, "model resolve failed: " + e.getMessage());
    }

    String prompt;
    try {
      // PromptAssembler.loadFile already prepends "classpath:prompts/", so paths here must be
      // relative to prompts/. Was previously "prompts/classifier.md" which produced a double
      // path like "classpath:prompts/prompts/classifier.md" → IOException → silent SKIPPED
      // and the LLM verifier never ran (run b171fa52 only had the heuristic verdict; the
      // upgrade-to-LEGACY check was missed). Audited via classifierReasoning persisted on the
      // run. 2026-05-02.
      // 2026-05-07 — V2/V3 unification renamed prompts/v2/ → prompts/. Was "v2/classifier.md"
      // since the unification, which produced "prompt assembly failed: Cannot load prompt file:
      // prompts/v2/classifier.md" on every run (visible on classifierReasoning of all 3 cmatrix
      // ProgramBench runs 2026-05-07). LLM verifier silently skipped on every run. Now
      // "classifier.md" matching the actual on-disk path.
      prompt =
          promptAssembler.assemble(
              "classifier.md",
              java.util.Map.of(
                  "HEURISTIC_VERDICT",
                  heuristic.strategy.name(),
                  "REPO_COUNT",
                  String.valueOf(repoFullNames == null ? 1 : repoFullNames.size()),
                  "REPO_NAMES",
                  repoFullNames == null || repoFullNames.isEmpty()
                      ? "(none)"
                      : String.join(", ", repoFullNames),
                  "PROJECT_TYPE",
                  projectType == null ? "unknown" : projectType,
                  "PROJECT_MEMORY",
                  projectMemorySnippet == null || projectMemorySnippet.isBlank()
                      ? "(no project memory yet)"
                      : projectMemorySnippet,
                  "SPEC",
                  spec));
    } catch (Exception e) {
      log.warn(
          "SmartClassifier prompt assembly failed: {} — using heuristic verdict", e.getMessage());
      metrics.recordClassifierVerifier("SKIPPED_PROMPT");
      return fallback(heuristic, "prompt assembly failed: " + e.getMessage());
    }

    // Split the assembled prompt into a system+user pair. The classifier
    // prompt is a single document; we use a synthetic system stub so the
    // provider knows it's a strict-output task.
    List<Message> messages = new ArrayList<>();
    messages.add(
        new SystemMessage(
            "You are a JSON-only spec classifier. "
                + "Respond with one JSON object and nothing else."));
    messages.add(new UserMessage(prompt));

    ExecutionContext ctx =
        new ExecutionContext(
            "classify-" + (projectId == null ? "anon" : projectId),
            "__planner_classifier__",
            Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath());

    try {
      ChatResponse resp =
          router.call(
              LlmRequest.builder()
                  .provider(resolved.getActualProvider().name().toLowerCase(Locale.ROOT))
                  .model(resolved.getModel())
                  .messages(messages)
                  .context(ctx)
                  .temperature(0.0)
                  .maxTokens(LLM_MAX_TOKENS)
                  .toolsOverride(Collections.emptyList())
                  .build());

      String raw = resp.getResult().getOutput().getText();
      if (raw == null || raw.isBlank()) {
        log.warn("SmartClassifier LLM returned empty body — using heuristic verdict");
        metrics.recordClassifierVerifier("SKIPPED_EMPTY_BODY");
        return fallback(heuristic, "LLM returned empty body");
      }

      ClassificationResult parsed = parseLlmJson(raw, heuristic, resp);
      if (parsed == null) {
        metrics.recordClassifierVerifier("SKIPPED_JSON_PARSE");
        return fallback(heuristic, "LLM JSON parse failed");
      }
      metrics.recordClassifierVerifier("OK");
      log.info(
          "SmartClassifier LLM → {} (heuristic was {}; tokens in/out={}/{}, ${})",
          parsed.strategy(),
          heuristic.strategy,
          parsed.inputTokens(),
          parsed.outputTokens(),
          String.format("%.6f", parsed.costUsd()));
      return parsed;
    } catch (Exception e) {
      log.warn("SmartClassifier LLM call failed: {} — using heuristic verdict", e.getMessage());
      metrics.recordClassifierVerifier("SKIPPED_LLM_CALL");
      return fallback(heuristic, "LLM call failed: " + truncForCopy(e.getMessage()));
    }
  }

  /** Truncate exception messages so the persisted classifierReasoning stays readable. */
  private static String truncForCopy(String s) {
    if (s == null) return "(no message)";
    return s.length() > 80 ? s.substring(0, 77) + "…" : s;
  }

  private ClassificationResult parseLlmJson(
      String raw, HeuristicVerdict heuristic, ChatResponse resp) {
    // Be forgiving of preamble — extract the first {...} block.
    String body = extractFirstJsonObject(raw);
    if (body == null) {
      log.warn(
          "SmartClassifier could not locate JSON object in LLM output: {}",
          raw.length() > 200 ? raw.substring(0, 200) + "…" : raw);
      return null;
    }
    try {
      JsonNode node = objectMapper.readTree(body);
      String strategyStr = node.path("strategy").asText(null);
      PlanStrategy strategy = PlanStrategy.fromStringSafe(strategyStr);
      if (strategy == null) {
        log.warn(
            "SmartClassifier LLM returned invalid strategy '{}' — using heuristic", strategyStr);
        return null;
      }
      int taskCount = node.path("estimatedTaskCount").asInt(defaultTaskCount(strategy));
      int repoTouchCount = node.path("estimatedRepoTouchCount").asInt(1);
      boolean requiresEpics = node.path("requiresEpics").asBoolean(false);
      String reasoning = node.path("reasoning").asText("");
      if (reasoning.length() > 280) reasoning = reasoning.substring(0, 277) + "…";

      long inputTokens = 0L, outputTokens = 0L;
      double cost = 0.0;
      if (resp.getMetadata() != null && resp.getMetadata().getUsage() != null) {
        Usage usage = resp.getMetadata().getUsage();
        if (usage.getPromptTokens() != null) inputTokens = usage.getPromptTokens();
        if (usage.getCompletionTokens() != null) outputTokens = usage.getCompletionTokens();
      }
      // Cost is recorded by RunCostTracker via LlmRouter — we don't
      // re-compute it here (the tracker is the source of truth). For the
      // ClassificationResult shape we report 0 and let the orchestrator
      // diff the run accumulator before/after to attribute the phase cost.

      return new ClassificationResult(
          strategy,
          taskCount,
          repoTouchCount,
          requiresEpics,
          reasoning,
          ClassificationResult.Source.LLM_VERIFIED,
          inputTokens,
          outputTokens,
          cost);
    } catch (Exception e) {
      log.warn("SmartClassifier JSON parse failed: {}", e.getMessage());
      return null;
    }
  }

  private static String extractFirstJsonObject(String raw) {
    int start = raw.indexOf('{');
    if (start < 0) return null;
    int depth = 0;
    boolean inString = false;
    boolean escape = false;
    for (int i = start; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (escape) {
        escape = false;
        continue;
      }
      if (c == '\\') {
        escape = true;
        continue;
      }
      if (c == '"') {
        inString = !inString;
        continue;
      }
      if (inString) continue;
      if (c == '{') depth++;
      else if (c == '}') {
        depth--;
        if (depth == 0) return raw.substring(start, i + 1);
      }
    }
    return null;
  }

  private static int defaultTaskCount(PlanStrategy strategy) {
    return switch (strategy) {
      case SINGLE_SHOT -> 1;
      case SMALL -> 4;
      case MEDIUM -> 10;
      case LARGE -> 25;
      case LEGACY -> 60;
    };
  }

  /**
   * [B-N5] (audit Sprint 2, 2026-04-30) — copy + reason discriminator fix. Was: every fallback path
   * persisted "LLM verifier unavailable" — sounds like an outage when in practice the cause is
   * varied (model resolve, prompt assembly, LLM 401, JSON parse, empty body). The audit's MEDIUM
   * Express run was misclassified as SMALL with exactly that copy, masking which subsystem failed.
   * Now persists the actual failure mode + flags this as a fallback explicitly so the dashboard /
   * reviewer can spot a wiring regression vs an intentional skip. Also adds a `[verifier-skipped]`
   * marker for ops grep on the run logs.
   */
  private ClassificationResult fallback(HeuristicVerdict heuristic, String reason) {
    String copy =
        "LLM verifier skipped ("
            + (reason == null || reason.isBlank() ? "unspecified" : reason)
            + ") — heuristic verdict: "
            + heuristic.reasoning;
    return new ClassificationResult(
        heuristic.strategy,
        defaultTaskCount(heuristic.strategy),
        heuristic.strategy == PlanStrategy.LARGE || heuristic.strategy == PlanStrategy.LEGACY
            ? 3
            : 1,
        heuristic.strategy == PlanStrategy.LEGACY,
        copy,
        ClassificationResult.Source.LLM_FALLBACK_TO_HEURISTIC,
        0L,
        0L,
        0.0);
  }

  // ──────────────────────────────────────────────────────────────────────
  // Cache helpers
  // ──────────────────────────────────────────────────────────────────────

  private static String cacheKey(String spec, List<String> repoFullNames, String projectId) {
    if (spec == null) return null;
    StringBuilder sb = new StringBuilder();
    sb.append(spec).append('\u0001');
    if (repoFullNames != null) {
      List<String> sorted = new ArrayList<>(repoFullNames);
      Collections.sort(sorted);
      sb.append(String.join(",", sorted));
    }
    sb.append('\u0001').append(projectId == null ? "" : projectId);
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) hex.append(String.format("%02x", b));
      return CACHE_PREFIX + hex.substring(0, 32); // 16 bytes is plenty for a cache key
    } catch (NoSuchAlgorithmException e) {
      return null;
    }
  }

  private void cachePut(String cacheKey, ClassificationResult result) {
    if (cacheKey == null || result == null) return;
    try {
      String value =
          objectMapper.writeValueAsString(
              java.util.Map.of(
                  "strategy", result.strategy().name(),
                  "estimatedTaskCount", result.estimatedTaskCount(),
                  "estimatedRepoTouchCount", result.estimatedRepoTouchCount(),
                  "requiresEpics", result.requiresEpics(),
                  "reasoning", result.reasoning() == null ? "" : result.reasoning(),
                  "source", result.source().name(),
                  "inputTokens", result.inputTokens(),
                  "outputTokens", result.outputTokens(),
                  "costUsd", result.costUsd()));
      redis.opsForValue().set(cacheKey, value, CACHE_TTL);
    } catch (Exception e) {
      log.debug("SmartClassifier cache write failed: {}", e.getMessage());
    }
  }

  private ClassificationResult parseCached(String json) {
    try {
      JsonNode node = objectMapper.readTree(json);
      PlanStrategy strategy = PlanStrategy.fromStringSafe(node.path("strategy").asText(null));
      if (strategy == null) return null;
      ClassificationResult.Source source;
      try {
        source =
            ClassificationResult.Source.valueOf(node.path("source").asText("HEURISTIC_FAST_PATH"));
      } catch (IllegalArgumentException e) {
        source = ClassificationResult.Source.HEURISTIC_FAST_PATH;
      }
      return new ClassificationResult(
          strategy,
          node.path("estimatedTaskCount").asInt(defaultTaskCount(strategy)),
          node.path("estimatedRepoTouchCount").asInt(1),
          node.path("requiresEpics").asBoolean(false),
          node.path("reasoning").asText(""),
          source,
          node.path("inputTokens").asLong(0L),
          node.path("outputTokens").asLong(0L),
          node.path("costUsd").asDouble(0.0));
    } catch (Exception e) {
      return null;
    }
  }
}
