package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.entity.RepoArchitectureDoc;

import org.junit.jupiter.api.Test;

/**
 * PR-K (2026-05-13) — pins the fingerprint canonicalisation contract. The fingerprint is the cache
 * key for the combined-arch LLM short-circuit; getting equality wrong here either burns money
 * (false miss → regenerates byte-identical doc) or stales the dashboard (false hit → keeps showing
 * the old doc after a real change).
 *
 * <p>Two invariants we want to keep stable:
 *
 * <ul>
 *   <li>Same set of (repo, sha) pairs must serialise identically regardless of insertion order;
 *   <li>Reading a legacy unsorted JSON row + re-canonicalising must equal the freshly-computed
 *       fingerprint when the underlying map is the same.
 * </ul>
 */
class ProjectArchitectureServiceFingerprintTest {

  private static final String CURRENT_FACTS =
      DeterministicProjectFactEvidence.BEGIN
          + "\n- Evidence-Format: `EXECUTESPEC_PROJECT_FACTS_V2`"
          + "\n- Repository: `owner/repo`"
          + "\n- Source revision: `1111111111111111111111111111111111111111`"
          + "\n- Status: `AVAILABLE`\n"
          + DeterministicProjectFactEvidence.ROUTES_BEGIN
          + "\n- No exact Spring handler response evidence was discovered.\n"
          + DeterministicProjectFactEvidence.ROUTES_END
          + "\n"
          + DeterministicProjectFactEvidence.END
          + "\n"
          + DeterministicProjectFactEvidence.SETUP_BEGIN
          + "\n- Evidence-Format: EXECUTESPEC_SETUP_SOURCES_V2"
          + "\n- Repository-Base64: b3duZXIvcmVwbw=="
          + "\n- Source-Revision: 1111111111111111111111111111111111111111"
          + "\n- Scm-Provider: GITHUB"
          + "\n- Source-Selection: COMPLETE"
          + "\n- No exact setup command source was selected.\n"
          + DeterministicProjectFactEvidence.SETUP_END;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void serialise_isStableAcrossInsertionOrder() {
    LinkedHashMap<String, String> a = new LinkedHashMap<>();
    a.put("owner/repo-a", "aaa");
    a.put("owner/repo-b", "bbb");

    LinkedHashMap<String, String> b = new LinkedHashMap<>();
    b.put("owner/repo-b", "bbb");
    b.put("owner/repo-a", "aaa");

    assertThat(ProjectArchitectureService.serialiseFingerprint(a))
        .isEqualTo(ProjectArchitectureService.serialiseFingerprint(b));
  }

  @Test
  void serialise_emptyOrNull_returnsNull() {
    assertThat(ProjectArchitectureService.serialiseFingerprint(null)).isNull();
    assertThat(ProjectArchitectureService.serialiseFingerprint(Map.of())).isNull();
  }

  @Test
  void serialise_differentShas_areDistinct() {
    Map<String, String> a = Map.of("owner/repo-a", "aaa");
    Map<String, String> b = Map.of("owner/repo-a", "bbb");

    assertThat(ProjectArchitectureService.serialiseFingerprint(a))
        .isNotEqualTo(ProjectArchitectureService.serialiseFingerprint(b));
  }

  @Test
  void fingerprintMatches_canonicalisesBeforeComparing() {
    // Legacy row written by the un-sorted LinkedHashMap path before this PR.
    String legacyStoredJson = "{\"owner/repo-b\":\"bbb\",\"owner/repo-a\":\"aaa\"}";

    LinkedHashMap<String, String> fresh = new LinkedHashMap<>();
    fresh.put("owner/repo-a", "aaa");
    fresh.put("owner/repo-b", "bbb");
    String current = ProjectArchitectureService.serialiseFingerprint(fresh);

    // Use the static overload so the test doesn't depend on the 19-arg Spring constructor.
    assertThat(
            ProjectArchitectureService.fingerprintMatches(objectMapper, legacyStoredJson, current))
        .isTrue();
  }

  @Test
  void fingerprintMatches_returnsFalseOnDifferentShas() {
    String storedJson = "{\"owner/repo-a\":\"aaa\"}";
    String currentFingerprint =
        ProjectArchitectureService.serialiseFingerprint(Map.of("owner/repo-a", "bbb"));

    assertThat(
            ProjectArchitectureService.fingerprintMatches(
                objectMapper, storedJson, currentFingerprint))
        .isFalse();
  }

  @Test
  void fingerprintMatches_malformedJson_returnsFalse() {
    String storedJson = "not-json{";
    String currentFingerprint =
        ProjectArchitectureService.serialiseFingerprint(Map.of("owner/repo-a", "aaa"));

    assertThat(
            ProjectArchitectureService.fingerprintMatches(
                objectMapper, storedJson, currentFingerprint))
        .isFalse();
  }

  @Test
  void fallbackRepoArchitecture_isNeverReusableAfterProviderRecovers() {
    RepoArchitectureDoc fallback =
        RepoArchitectureDoc.builder()
            .content(
                "# repo — Architecture\n\n_LLM generation failed. Showing raw codemap instead._")
            .modelUsed("fallback")
            .build();

    assertThat(ProjectArchitectureService.isReusableRepoArchitecture(fallback)).isFalse();
  }

  @Test
  void fallbackRepoArchitecture_neverPersistsProviderExceptionText() {
    String markdown =
        ProjectArchitectureService.fallbackRepoArchitectureMarkdown(
            "owner/repo", "## Files\n- src/App.java");

    assertThat(markdown)
        .contains("LLM generation failed")
        .contains("src/App.java")
        .doesNotContain("api-key", "Bearer", "exception");
  }

  @Test
  void legacyProviderGeneratedRepoArchitectureWithoutDeterministicSurface_isNotReusable() {
    RepoArchitectureDoc generated =
        RepoArchitectureDoc.builder()
            .content("# repo — Architecture\n\n## High-Level Design\nGrounded content")
            .modelUsed("deepseek-chat")
            .build();

    assertThat(ProjectArchitectureService.isReusableRepoArchitecture(generated)).isFalse();
  }

  @Test
  void providerGeneratedRepoArchitectureWithCurrentDeterministicSurface_isReusable() {
    RepoArchitectureDoc generated =
        RepoArchitectureDoc.builder()
            .content(
                "# repo — Architecture\n\n"
                    + DeterministicArchitectureEvidence.HTTP_BEGIN
                    + "\n- Status: `AVAILABLE`\n"
                    + DeterministicArchitectureEvidence.HTTP_END
                    + "\n"
                    + CURRENT_FACTS)
            .modelUsed("deepseek-chat")
            .build();

    assertThat(ProjectArchitectureService.isReusableRepoArchitecture(generated)).isTrue();
  }

  @Test
  void httpOnlyLegacyRepoArchitectureIsNotReusableUnderProjectFactContract() {
    RepoArchitectureDoc generated =
        RepoArchitectureDoc.builder()
            .content(
                DeterministicArchitectureEvidence.HTTP_BEGIN
                    + "\n- Status: `AVAILABLE`\n"
                    + DeterministicArchitectureEvidence.HTTP_END)
            .modelUsed("deepseek-chat")
            .build();

    assertThat(ProjectArchitectureService.isReusableRepoArchitecture(generated)).isFalse();
  }

  @Test
  void deterministicCombinedFallback_cannotPoisonTheFingerprintCache() {
    ProjectArchitectureDoc fallback =
        ProjectArchitectureDoc.builder()
            .content("# Project architecture (deterministic)\n\nConcatenated fallback")
            .build();

    assertThat(ProjectArchitectureService.isReusableProjectArchitecture(fallback)).isFalse();
  }

  @Test
  void legacyProviderGeneratedCombinedArchitectureWithoutDeterministicSurface_isNotReusable() {
    ProjectArchitectureDoc generated =
        ProjectArchitectureDoc.builder()
            .content("# TicTacToe — Combined architecture\n\n## Overview\nGrounded content")
            .build();

    assertThat(ProjectArchitectureService.isReusableProjectArchitecture(generated)).isFalse();
  }

  @Test
  void providerGeneratedCombinedArchitectureWithCurrentDeterministicSurface_isReusable() {
    ProjectArchitectureDoc generated =
        ProjectArchitectureDoc.builder()
            .content(
                "# Combined\n\n"
                    + DeterministicArchitectureEvidence.HTTP_BEGIN
                    + "\n- Status: `AVAILABLE`\n"
                    + DeterministicArchitectureEvidence.HTTP_END
                    + "\n"
                    + CURRENT_FACTS)
            .build();

    assertThat(ProjectArchitectureService.isReusableProjectArchitecture(generated)).isTrue();
  }
}
