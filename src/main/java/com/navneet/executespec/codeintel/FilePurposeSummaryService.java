package com.navneet.executespec.codeintel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.agent.FallbackLlmRouter;
import com.navneet.executespec.codeintel.entity.FilePurposeSummary;
import com.navneet.executespec.codeintel.repository.FilePurposeSummaryRepository;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PR-N2 (V196, 2026-05-14) — generates 2-3 sentence purpose summaries for source files via a
 * LIGHTWEIGHT-tier LLM call, with SHA-keyed caching so unchanged files cost zero on subsequent
 * codemap regens.
 *
 * <p>Design rationale (the deferred handoff doc that originally captured this work was deleted
 * 2026-05-14 once both PR-N1 + PR-N2 shipped — full context in the PR-N2 commit message):
 *
 * <ul>
 *   <li>Per-file (not per-function) — keeps the call count proportional to file count (~40) not
 *       function count (~1,200). 240× cost reduction at the same product value.
 *   <li>SHA-keyed cache — content hash is the natural eviction key. A renamed file gets a new row
 *       at the new path; the old row stays until the eviction sweeper reaps it. A modified file
 *       produces a new SHA → new row → cache miss → LLM call.
 *   <li>Feature-flag gated — {@code codemap.purpose-summaries} (default OFF). The flag lives in
 *       {@code feature_flags} via {@link FeatureFlagService}. Rollout plan: enable on dev first,
 *       observe cost telemetry for a week, then enable per-tier in prod.
 *   <li>Best-effort — every public method catches Exception and returns gracefully. A failing
 *       summary never breaks the codemap regen path; the file just lacks a purpose line.
 * </ul>
 *
 * <p>Cost: ~$0.0001 per file per generation on Haiku (LIGHTWEIGHT tier). Steady-state with cache:
 * <$0.001 per project per week assuming typical churn (5-10 files changed/week).
 *
 * <p>Not yet wired to {@link CodeMapGenerator}'s render path — the integration is a separate patch
 * so reviewers can audit the cache shape independently from the render-time logic. The caller-side
 * integration is gated on the same feature flag.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FilePurposeSummaryService {

  private static final String FLAG_PURPOSE_SUMMARIES = "codemap.purpose-summaries";

  private static final String SUMMARY_SYSTEM =
      """
      You are summarising the purpose of a single source file for an engineering codemap.
      Output exactly 2-3 sentences, no list, no code, no headings. Cover:
        1. What this file is responsible for (its primary role)
        2. The most important collaborators it talks to (if visible from context)
        3. Any non-obvious side effects worth flagging (events fired, persistence, async work)

      Rules:
        - Write in natural English, not "this file does X". Lead with the noun.
        - Cite specific function names from the file when they sharpen the description.
        - Do NOT speculate beyond what the content shows. If it's a config file, say so.
        - Aim for 40-80 words total.
      """;

  /**
   * Max bytes of file content fed to the LLM. Keeps prompt cost bounded — beyond ~6KB the marginal
   * information density drops sharply for purpose-summary purposes.
   */
  private static final int MAX_CONTENT_BYTES = 6_144;

  private final FilePurposeSummaryRepository repo;
  private final ModelResolver modelResolver;
  private final FallbackLlmRouter fallbackLlmRouter;
  private final FeatureFlagService featureFlagService;
  private final RunCostTracker costTracker;

  /**
   * Public entry point — returns a cached or freshly-generated purpose summary for {@code
   * (repoFullName, filePath, fileContent)}. Returns {@link Optional#empty()} when the feature flag
   * is OFF, content is blank, or the LLM call failed.
   *
   * <p>Side-effect: on cache miss, writes a new row to {@code file_purpose_summary}. On cache hit,
   * no DB write happens.
   *
   * @param repoFullName e.g. "owner/repo"
   * @param filePath workspace-relative path
   * @param fileContent file content (will be truncated to {@link #MAX_CONTENT_BYTES})
   * @param provider LLM provider override; null = ANTHROPIC default
   * @return optional summary; empty on disabled-flag / blank-input / LLM failure
   */
  public Optional<String> summariseFile(
      String repoFullName, String filePath, String fileContent, LlmProvider provider) {
    if (!shouldGenerate()) return Optional.empty();
    if (repoFullName == null || filePath == null || fileContent == null) return Optional.empty();
    if (fileContent.isBlank()) return Optional.empty();

    String fileSha = sha256Hex(fileContent);
    Optional<FilePurposeSummary> cached =
        repo.findByRepoFullNameAndFilePathAndFileSha(repoFullName, filePath, fileSha);
    if (cached.isPresent()) {
      String existing = cached.get().getSummary();
      if (existing != null && !existing.isBlank()) {
        return Optional.of(existing);
      }
      // Defensive: cached row with empty content — treat as miss + regenerate. Should never
      // happen since the writer-side rejects empty summaries but kept here so a partial-write
      // artefact can't poison subsequent reads.
    }

    return generateAndPersist(repoFullName, filePath, fileSha, fileContent, provider);
  }

  /**
   * Generate + persist path. Public-static-friendly logic split out so test can exercise the
   * persist without crossing the cache check.
   */
  @Transactional
  protected Optional<String> generateAndPersist(
      String repoFullName,
      String filePath,
      String fileSha,
      String fileContent,
      LlmProvider provider) {
    String truncated = truncate(fileContent, MAX_CONTENT_BYTES);
    String userPayload =
        "Repository: " + repoFullName + "\nFile: " + filePath + "\n\n```\n" + truncated + "\n```\n";
    LlmProvider effective = provider != null ? provider : LlmProvider.ANTHROPIC;

    try {
      ResolvedModel resolved = modelResolver.resolve("FilePurposeSummaryService", effective);
      Prompt prompt =
          new Prompt(List.of(new SystemMessage(SUMMARY_SYSTEM), new UserMessage(userPayload)));
      ChatResponse response =
          fallbackLlmRouter.callWithFallback(
              resolved.providerName(),
              resolved.getModel(),
              prompt,
              "file-purpose-" + filePath,
              "file-purpose-summary",
              FallbackLlmRouter.FallbackMode.IMMEDIATE,
              0,
              false);
      String summary = response.getResult().getOutput().getText();
      if (summary == null || summary.isBlank()) {
        log.debug("FilePurposeSummary: empty LLM response for {}:{}", repoFullName, filePath);
        return Optional.empty();
      }
      summary = summary.trim();
      Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
      int inTok = usage != null && usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
      int outTok =
          usage != null && usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
      double cost =
          costTracker.computeCost(resolved.providerName(), resolved.getModel(), inTok, outTok);
      try {
        repo.save(
            FilePurposeSummary.builder()
                .repoFullName(repoFullName)
                .filePath(filePath)
                .fileSha(fileSha)
                .summary(summary)
                .modelUsed(resolved.getModel())
                .inputTokens(inTok)
                .outputTokens(outTok)
                .costUsd(cost)
                .build());
      } catch (Exception persistEx) {
        // A concurrent regen on the same project + same SHA can race us. The unique
        // constraint catches the dupe; we treat that as success because someone else just
        // wrote the same row. Anything else we log + still return the summary (cache will
        // refill on the next call).
        log.debug(
            "FilePurposeSummary persist race for {}:{}: {}",
            repoFullName,
            filePath,
            persistEx.getMessage());
      }
      return Optional.of(summary);
    } catch (Exception e) {
      log.debug(
          "FilePurposeSummary LLM call failed for {}:{}: {}",
          repoFullName,
          filePath,
          e.getMessage());
      return Optional.empty();
    }
  }

  /** Feature-flag gate — exposed package-private for tests. */
  boolean shouldGenerate() {
    try {
      return featureFlagService.isEnabled(FLAG_PURPOSE_SUMMARIES);
    } catch (Exception e) {
      // FeatureFlagService failure → behave as if disabled.
      log.debug("FilePurposeSummary: flag check failed, treating as disabled: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Deterministic content-hash for cache keying. SHA-256 hex — not git's blob hash because we want
   * this stable across pulls / forks / non-GitHub providers. Returns the hex digest as a 64-char
   * String.
   */
  static String sha256Hex(String content) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 ships with every JVM since the dawn of time; truly impossible.
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  static String truncate(String s, int maxBytes) {
    if (s == null) return "";
    byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= maxBytes) return s;
    // Byte-truncate is safe because we re-decode UTF-8; if we cut a multi-byte char in half
    // we just drop the trailing partial code unit. Acceptable for the LLM prompt — it's a
    // summary input, not the canonical content.
    return new String(bytes, 0, maxBytes, StandardCharsets.UTF_8);
  }
}
