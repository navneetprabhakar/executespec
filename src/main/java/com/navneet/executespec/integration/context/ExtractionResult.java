package com.navneet.executespec.integration.context;

import java.util.List;

/**
 * Result of scanning a user request for external resource references.
 *
 * @param enrichedRequest the original request augmented with fetched context (or unchanged)
 * @param clarificationNeeded true if the user mentioned Jira/Confluence but no resolvable reference
 *     was found
 * @param clarificationPrompt human-readable prompt to show the user (non-null only when
 *     clarificationNeeded)
 * @param extractedRefs list of references that were successfully fetched
 */
public record ExtractionResult(
    String enrichedRequest,
    boolean clarificationNeeded,
    String clarificationPrompt,
    List<ExtractedRef> extractedRefs) {
  public record ExtractedRef(String type, String identifier, String title) {}

  /** No references found — returns the original request unchanged. */
  public static ExtractionResult unchanged(String originalRequest) {
    return new ExtractionResult(originalRequest, false, null, List.of());
  }

  /** References extracted and enriched into the request. */
  public static ExtractionResult enriched(String enrichedRequest, List<ExtractedRef> refs) {
    return new ExtractionResult(enrichedRequest, false, null, refs);
  }

  /** Ambiguous mention found — clarification required. */
  public static ExtractionResult needsClarification(String originalRequest, String prompt) {
    return new ExtractionResult(originalRequest, true, prompt, List.of());
  }
}
