package com.navneet.executespec.reviewer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * P1.1 (GA-readiness, 2026-05-06) — pins the head+tail truncation behaviour added to {@link
 * AccuracyAssessmentService#truncateForSnippet}.
 *
 * <p>Background: the old per-file cap was 2,500 chars head-only. On run {@code 0444c95f} that
 * emitted "controller exists but content not readable" 14× because the LLM saw a router file's
 * imports + first 2 endpoints and missed the rest. Fix: 10K cap with head 6K + tail 3K so handlers
 * near the end of long files stay visible to the assessor.
 */
class AccuracyAssessmentTruncateForSnippetTest {

  @Test
  void shortFile_returnedAsIs() {
    String content = "def hello():\n    return 'world'\n";
    assertThat(AccuracyAssessmentService.truncateForSnippet(content)).isEqualTo(content);
  }

  @Test
  void atCapBoundary_returnedAsIs() {
    String content = "x".repeat(AccuracyAssessmentService.PER_FILE_CHAR_CAP);
    assertThat(AccuracyAssessmentService.truncateForSnippet(content)).isEqualTo(content);
    assertThat(AccuracyAssessmentService.truncateForSnippet(content)).hasSize(10_000);
  }

  @Test
  void longFile_emitsHeadTailWithTruncationMarker() {
    // Build a file that's 30K chars: 10K of header, 10K of middle, 10K of tail.
    String head = "HEAD_".repeat(2_000); // 10,000 chars
    String middle = "MID_".repeat(2_500); // 10,000 chars
    String tail = "TAIL_".repeat(2_000); // 10,000 chars
    String content = head + middle + tail;
    assertThat(content).hasSize(30_000);

    String result = AccuracyAssessmentService.truncateForSnippet(content);

    // Head 6K (HEAD_CHARS) + tail 3K (TAIL_CHARS) + marker text
    assertThat(result.length()).isGreaterThan(9_000);
    assertThat(result.length()).isLessThan(9_500); // 9,000 + ~70 char marker
    assertThat(result).startsWith("HEAD_");
    assertThat(result).endsWith("TAIL_");
    assertThat(result).contains("... [truncated");
    assertThat(result).contains("chars in the middle");
    assertThat(result).contains("file is 30000 chars total");
    // Middle content is NOT in the snippet
    assertThat(result).doesNotContain("MID_");
  }

  @Test
  void veryLongRouterFile_visibleToBoth_imports_and_lastHandlers() {
    // Simulates the run-0444c95f failure: a FastAPI router with imports + 5 endpoints, total
    // 25K chars. Old behavior (2,500 cap, head-only) showed imports + endpoint #1. New behavior
    // (10K cap, head+tail) shows imports + first 2 endpoints + last 2 endpoints.
    String imports = "from fastapi import APIRouter, Depends\n".repeat(50); // ~2K chars
    String endpoint1 =
        "@router.post('/contacts')\nasync def create_contact():\n    return {}\n".repeat(80); // ~5K
    String endpoint2 =
        "@router.get('/contacts/{id}')\nasync def get_contact(id):\n    return {}\n".repeat(80);
    String endpoint3 =
        "@router.put('/contacts/{id}')\nasync def update_contact(id):\n    return {}\n".repeat(80);
    String endpoint4 =
        "@router.delete('/contacts/{id}')\nasync def delete_contact(id):\n    return {}\n"
            .repeat(80);
    String endpoint5 =
        "@router.post('/contacts/{id}/notes')\nasync def add_note(id, note):\n    return {}\n"
            .repeat(80);
    String content = imports + endpoint1 + endpoint2 + endpoint3 + endpoint4 + endpoint5;

    String result = AccuracyAssessmentService.truncateForSnippet(content);

    // Imports must be visible (they're at the top)
    assertThat(result).contains("from fastapi import APIRouter");
    // The LAST endpoint (POST /contacts/{id}/notes — the spec item that was failing) must be
    // visible thanks to the tail. Without the tail this assertion fails.
    assertThat(result).contains("/contacts/{id}/notes");
    // Truncation marker present — explicit signal to the LLM that there's a middle section
    // it can't see, so it should not infer "missing" from absence.
    assertThat(result).contains("[truncated");
  }

  @Test
  void nullContent_returnsEmpty() {
    assertThat(AccuracyAssessmentService.truncateForSnippet(null)).isEmpty();
  }

  @Test
  void truncationMarkerCarriesAccurateOmittedCount() {
    String content = "a".repeat(50_000); // 50K
    String result = AccuracyAssessmentService.truncateForSnippet(content);
    int omitted =
        50_000 - AccuracyAssessmentService.HEAD_CHARS - AccuracyAssessmentService.TAIL_CHARS;
    assertThat(result).contains("[truncated " + omitted + " chars");
    assertThat(result).contains("file is 50000 chars total");
  }
}
