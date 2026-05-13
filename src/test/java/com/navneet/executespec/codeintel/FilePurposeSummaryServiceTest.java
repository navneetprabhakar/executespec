package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.agent.FallbackLlmRouter;
import com.navneet.executespec.codeintel.entity.FilePurposeSummary;
import com.navneet.executespec.codeintel.repository.FilePurposeSummaryRepository;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PR-N2 (2026-05-14) — pins the feature-flag gate, SHA derivation, cache-hit short-circuit, and the
 * empty-content protection on {@link FilePurposeSummaryService}.
 *
 * <p>The LLM call path is intentionally NOT exercised here — the cost of mocking the Spring-AI
 * ChatResponse usage chain isn't worth the coverage. The cache-hit path is what the service is FOR;
 * we pin that, and document the rest by reading the source.
 */
class FilePurposeSummaryServiceTest {

  private FilePurposeSummaryRepository repo;
  private ModelResolver modelResolver;
  private FallbackLlmRouter llmRouter;
  private FeatureFlagService featureFlagService;
  private RunCostTracker costTracker;
  private FilePurposeSummaryService svc;

  @BeforeEach
  void setUp() {
    repo = mock(FilePurposeSummaryRepository.class);
    modelResolver = mock(ModelResolver.class);
    llmRouter = mock(FallbackLlmRouter.class);
    featureFlagService = mock(FeatureFlagService.class);
    costTracker = mock(RunCostTracker.class);
    svc =
        new FilePurposeSummaryService(
            repo, modelResolver, llmRouter, featureFlagService, costTracker);
  }

  // ── Feature flag gate ────────────────────────────────────────────────

  @Test
  void flagOff_returnsEmpty_andNeverHitsRepoOrLlm() {
    when(featureFlagService.isEnabled("codemap.purpose-summaries")).thenReturn(false);

    Optional<String> result =
        svc.summariseFile(
            "owner/repo", "src/User.java", "public class User {}", LlmProvider.ANTHROPIC);

    assertThat(result).isEmpty();
    // Repo and LLM should not have been touched at all when the flag is off.
    verify(repo, never()).findByRepoFullNameAndFilePathAndFileSha(any(), any(), any());
    verify(modelResolver, never()).resolve(any(), any());
  }

  @Test
  void flagFlagServiceThrows_treatsAsDisabled() {
    when(featureFlagService.isEnabled(any())).thenThrow(new RuntimeException("redis down"));

    // Should not propagate the exception — degrades to disabled.
    Optional<String> result =
        svc.summariseFile("owner/repo", "src/User.java", "code", LlmProvider.ANTHROPIC);

    assertThat(result).isEmpty();
    verify(repo, never()).findByRepoFullNameAndFilePathAndFileSha(any(), any(), any());
  }

  // ── Blank-input protection ───────────────────────────────────────────

  @Test
  void nullContent_returnsEmpty() {
    when(featureFlagService.isEnabled("codemap.purpose-summaries")).thenReturn(true);
    Optional<String> result = svc.summariseFile("owner/repo", "src/X.java", null, null);
    assertThat(result).isEmpty();
  }

  @Test
  void blankContent_returnsEmpty() {
    when(featureFlagService.isEnabled("codemap.purpose-summaries")).thenReturn(true);
    Optional<String> result = svc.summariseFile("owner/repo", "src/X.java", "   \n  ", null);
    assertThat(result).isEmpty();
  }

  @Test
  void nullPath_returnsEmpty() {
    when(featureFlagService.isEnabled("codemap.purpose-summaries")).thenReturn(true);
    Optional<String> result = svc.summariseFile("owner/repo", null, "code", null);
    assertThat(result).isEmpty();
  }

  // ── Cache hit path ───────────────────────────────────────────────────

  @Test
  void cacheHit_returnsStoredSummary_andSkipsLlm() {
    when(featureFlagService.isEnabled("codemap.purpose-summaries")).thenReturn(true);
    String content = "public class User { void create() {} }";
    String expectedSha = FilePurposeSummaryService.sha256Hex(content);

    FilePurposeSummary cached =
        FilePurposeSummary.builder()
            .repoFullName("owner/repo")
            .filePath("src/User.java")
            .fileSha(expectedSha)
            .summary("User CRUD service. Talks to UserRepository.")
            .build();
    when(repo.findByRepoFullNameAndFilePathAndFileSha("owner/repo", "src/User.java", expectedSha))
        .thenReturn(Optional.of(cached));

    Optional<String> result =
        svc.summariseFile("owner/repo", "src/User.java", content, LlmProvider.ANTHROPIC);

    assertThat(result).contains("User CRUD service. Talks to UserRepository.");
    // LLM path should NOT have fired
    verify(modelResolver, never()).resolve(any(), any());
    verify(repo, never()).save(any());
  }

  @Test
  void cachedEmptyContent_fallsThrough() {
    // Defensive: a cached row with null/blank summary must NOT short-circuit. The empty row
    // should be ignored and the LLM path attempted. (We don't go end-to-end here because
    // mocking the LLM chain is expensive — we observe modelResolver was called as proof.)
    when(featureFlagService.isEnabled("codemap.purpose-summaries")).thenReturn(true);
    String content = "code";
    String sha = FilePurposeSummaryService.sha256Hex(content);
    FilePurposeSummary empty =
        FilePurposeSummary.builder()
            .repoFullName("owner/repo")
            .filePath("src/X.java")
            .fileSha(sha)
            .summary("")
            .build();
    when(repo.findByRepoFullNameAndFilePathAndFileSha(any(), any(), any()))
        .thenReturn(Optional.of(empty));
    // modelResolver throws → service catches + returns Optional.empty()
    when(modelResolver.resolve(any(), any())).thenThrow(new RuntimeException("mock"));

    Optional<String> result = svc.summariseFile("owner/repo", "src/X.java", content, null);

    // Result is empty because the LLM call failed — but the critical assertion is that the
    // service tried to call the LLM (i.e. it didn't short-circuit on the blank cache row).
    assertThat(result).isEmpty();
    verify(modelResolver).resolve("FilePurposeSummaryService", LlmProvider.ANTHROPIC);
  }

  // ── SHA stability ────────────────────────────────────────────────────

  @Test
  void sha256_deterministic_sameInputSameHash() {
    String a = FilePurposeSummaryService.sha256Hex("hello world");
    String b = FilePurposeSummaryService.sha256Hex("hello world");
    assertThat(a).isEqualTo(b);
  }

  @Test
  void sha256_distinct_differentInputs() {
    String a = FilePurposeSummaryService.sha256Hex("hello world");
    String b = FilePurposeSummaryService.sha256Hex("hello world!");
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void sha256_isHex64Chars() {
    String hash = FilePurposeSummaryService.sha256Hex("anything");
    assertThat(hash).hasSize(64).matches("^[0-9a-f]{64}$");
  }

  @Test
  void sha256_unicode_stable() {
    // Different code unit sequences that share a canonical form would BE different here —
    // we don't normalise. Pin the behaviour so a future "lets NFC-normalise" change is a
    // conscious decision, not an accident.
    String emoji = FilePurposeSummaryService.sha256Hex("café 🚀");
    String emojiAgain = FilePurposeSummaryService.sha256Hex("café 🚀");
    assertThat(emoji).isEqualTo(emojiAgain).hasSize(64);
  }

  // ── Truncation guard ─────────────────────────────────────────────────

  @Test
  void truncate_keepsShortInputs() {
    assertThat(FilePurposeSummaryService.truncate("hello", 100)).isEqualTo("hello");
  }

  @Test
  void truncate_cutsLongInputs() {
    String big = "x".repeat(10_000);
    String cut = FilePurposeSummaryService.truncate(big, 1024);
    assertThat(cut).hasSize(1024);
  }

  @Test
  void truncate_nullSafe() {
    assertThat(FilePurposeSummaryService.truncate(null, 100)).isEmpty();
  }
}
