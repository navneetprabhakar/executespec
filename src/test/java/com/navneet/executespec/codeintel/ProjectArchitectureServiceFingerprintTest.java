package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

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
}
