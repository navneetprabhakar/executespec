package com.navneet.executespec.reviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Pins the keyword-extraction logic used by Layer 3 grep verification (added 2026-05-10) of
 * AccuracyAssessmentService. Specifically pins:
 *
 * <ul>
 *   <li>tokens shorter than 4 chars are dropped (would over-match)
 *   <li>common-english + spec-shaped stopwords are dropped (no signal)
 *   <li>numeric-only tokens are dropped
 *   <li>inline citations like {@code [src/foo.ts:22-35]} are stripped before tokenizing (so the
 *       grep doesn't match the citation itself rather than the implementation)
 *   <li>output is bounded to 6 tokens to keep the verify pass cheap
 *   <li>null / blank / no-distinctive-tokens inputs return an empty set, which the caller treats as
 *       "no tokens, leave the entry as MISSING"
 * </ul>
 */
class AccuracyAssessmentExtractKeywordsTest {

  @Test
  void distinctive_phrase_yields_distinctive_tokens() {
    Set<String> tokens =
        AccuracyAssessmentService.extractKeywords(
            "Implement two-factor authentication with TOTP rotation");
    // implement → stopword. with → stopword. tokens >= 4 chars: two/factor/authentication/totp/
    // rotation. "two" is < 4 → dropped. Expected distinctive set:
    assertTrue(tokens.contains("factor"));
    assertTrue(tokens.contains("authentication"));
    assertTrue(tokens.contains("totp"));
    assertTrue(tokens.contains("rotation"));
    assertFalse(tokens.contains("with"), "stopword 'with' must be dropped");
    assertFalse(tokens.contains("implement"), "spec-shaped stopword 'implement' must be dropped");
    assertFalse(tokens.contains("two"), "tokens < 4 chars must be dropped");
  }

  @Test
  void stopword_only_input_returns_empty() {
    // Pure spec filler — every word would be dropped.
    Set<String> tokens =
        AccuracyAssessmentService.extractKeywords("Should support each requirement");
    assertEquals(Set.of(), tokens, "stopword-only input should yield no distinctive tokens");
  }

  @Test
  void inline_citation_is_stripped_before_tokenizing() {
    // Real LLM output shape from the prompt's IMPLEMENTED rule — citation is suffixed in
    // brackets and SHOULD NOT contribute tokens (otherwise grep would match the citation
    // path itself in the output).
    Set<String> tokens =
        AccuracyAssessmentService.extractKeywords(
            "Slug length is 7 chars [src/services/shortener.ts:22-35]");
    // Expected: slug + length + chars. shortener / services / src must be stripped via the
    // [...] regex.
    assertTrue(tokens.contains("slug"));
    assertTrue(tokens.contains("length"));
    assertTrue(tokens.contains("chars"));
    assertFalse(tokens.contains("shortener"), "citation file path must not leak into tokens");
    assertFalse(tokens.contains("services"));
    assertFalse(tokens.contains("src"));
  }

  @Test
  void numeric_only_tokens_dropped() {
    Set<String> tokens = AccuracyAssessmentService.extractKeywords("Cache TTL is 3600 seconds");
    assertTrue(tokens.contains("cache"));
    assertTrue(tokens.contains("seconds"));
    assertFalse(tokens.contains("3600"), "numeric-only tokens must be dropped");
  }

  @Test
  void cap_at_six_tokens() {
    // 10 distinctive words → only first 6 should survive (insertion order preserved by
    // LinkedHashSet).
    Set<String> tokens =
        AccuracyAssessmentService.extractKeywords(
            "alpha bravo charlie delta echo foxtrot golf hotel india juliet");
    assertEquals(6, tokens.size(), "must cap at 6 tokens");
    // Insertion order check (best-effort — just first should be alpha, last bounded should be
    // foxtrot).
    assertTrue(tokens.contains("alpha"));
    assertTrue(tokens.contains("foxtrot"));
    assertFalse(tokens.contains("hotel"), "7th+ token must be excluded by the cap");
  }

  @Test
  void null_input_returns_empty() {
    assertEquals(Set.of(), AccuracyAssessmentService.extractKeywords(null));
  }

  @Test
  void blank_input_returns_empty() {
    assertEquals(Set.of(), AccuracyAssessmentService.extractKeywords(""));
    assertEquals(Set.of(), AccuracyAssessmentService.extractKeywords("   \t\n "));
  }

  @Test
  void deduplicates_repeated_tokens() {
    // "rate" appears twice — should still count as one token.
    Set<String> tokens =
        AccuracyAssessmentService.extractKeywords("Rate limit policy enforces rate ceiling");
    long rateCount = tokens.stream().filter("rate"::equals).count();
    assertEquals(1L, rateCount, "duplicate tokens must be deduplicated");
  }

  @Test
  void mixed_case_normalises_to_lowercase() {
    Set<String> tokens = AccuracyAssessmentService.extractKeywords("UserId Authentication TOTP");
    // All output should be lowercase.
    for (String t : tokens) {
      assertEquals(t.toLowerCase(), t, "tokens must be lowercase");
    }
    assertTrue(tokens.contains("userid"));
    assertTrue(tokens.contains("authentication"));
    assertTrue(tokens.contains("totp"));
  }

  @Test
  void grep_literal_pattern_uses_grep_compatible_escaping() {
    assertEquals("plain", AccuracyAssessmentService.grepLiteralPattern("plain"));
    assertEquals("foo\\.bar", AccuracyAssessmentService.grepLiteralPattern("foo.bar"));
    assertEquals(
        "price\\$\\[v1\\]\\*", AccuracyAssessmentService.grepLiteralPattern("price$[v1]*"));
    assertEquals(
        "path\\\\to\\\\file", AccuracyAssessmentService.grepLiteralPattern("path\\to\\file"));
    assertFalse(
        AccuracyAssessmentService.grepLiteralPattern("foo.bar").contains("\\Q"),
        "grep does not understand Java Pattern.quote markers");
  }
}
