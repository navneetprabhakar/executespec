package com.navneet.executespec.reviewer.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Structured accuracy assessment of generated code against the original user specification.
 *
 * <p>Produced by {@link com.navneet.executespec.reviewer.AccuracyAssessmentService} as Phase 3 of
 * the review pipeline, after code inspection and auto-fix.
 *
 * <p>Accuracy is measured across four dimensions:
 *
 * <ul>
 *   <li><b>Spec adherence</b> — what % of explicitly stated requirements were implemented
 *   <li><b>Endpoint coverage</b> — for API specs, which endpoints are present/missing
 *   <li><b>Schema correctness</b> — do request/response payloads match the spec
 *   <li><b>Edge case handling</b> — were specified edge cases (validation, error cases) handled
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AccuracyReport {

  /**
   * Overall spec adherence score: 0–100. 100 = every requirement implemented correctly. 0 = nothing
   * from the spec was implemented.
   */
  private int specAdherenceScore;

  /**
   * Letter grade derived from specAdherenceScore. A = 90–100, B = 80–89, C = 70–79, D = 60–69, F =
   * below 60.
   */
  private String overallAccuracyGrade;

  /** Brief explanation of the grade — what drove the score up or down. */
  private String gradingNotes;

  /** Requirements explicitly stated in the spec that were correctly and fully implemented. */
  @Builder.Default private List<String> implementedRequirements = List.of();

  /**
   * Requirements explicitly stated in the spec that are absent from the generated code. Each entry
   * should reference the specific requirement text, not be generic.
   */
  @Builder.Default private List<String> missingRequirements = List.of();

  /**
   * Requirements that exist in the code but are only partially correct — e.g., endpoint exists but
   * validation logic is missing.
   */
  @Builder.Default private List<String> partialRequirements = List.of();

  /**
   * 2026-05-10 — requirements where the assessor could not find conclusive evidence of presence OR
   * absence. The implementing file may not have been in the key-files snippet, the snippet may have
   * been truncated past the relevant section, or the requirement's vocabulary may not surface in
   * the code. UNKNOWN is the explicit "I don't know" outcome and is NOT counted as a gap (it does
   * not drag {@link #specAdherenceScore} or {@link #incompleteRequirementsCount}).
   *
   * <p>This field exists to absorb the most common AccuracyAssessment hallucination class: the LLM
   * defaulting to MISSING when it simply hasn't seen the relevant code. With this outcome, the
   * prompt can reserve MISSING for cases with positive negative-evidence (stub,
   * throw-not-implemented, empty handler) and route absence-of-evidence here instead.
   */
  @Builder.Default private List<String> unknownRequirements = List.of();

  /**
   * API endpoint coverage breakdown (populated for API-type projects). Null for non-API projects
   * (static sites, CLI tools, etc.)
   */
  private EndpointCoverage endpointCoverage;

  /**
   * How accurately do request/response schemas match the spec? Values: HIGH (matches exactly),
   * MEDIUM (minor deviations), LOW (significant mismatches)
   */
  private String schemaAccuracy;

  /**
   * Test coverage level relative to what the spec implied should be tested. Values: NONE, PARTIAL,
   * GOOD, COMPREHENSIVE
   */
  private String testCoverageLevel;

  /**
   * Coverage of non-functional requirements: auth, rate limiting, pagination, error handling, etc.
   * Values: COMPLETE, PARTIAL, MISSING
   */
  private String nonFunctionalCoverage;

  // ──────────────────────────────────────────────────────────────────────────
  // Nested record: Endpoint coverage breakdown
  // ──────────────────────────────────────────────────────────────────────────

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class EndpointCoverage {

    /** All HTTP endpoints explicitly requested in the spec (e.g., "POST /api/users"). */
    @Builder.Default private List<String> required = List.of();

    /** Endpoints found in the generated code that match spec requirements. */
    @Builder.Default private List<String> implemented = List.of();

    /** Endpoints specified in the spec but absent from the generated code. */
    @Builder.Default private List<String> missing = List.of();

    /**
     * Endpoints present in the code but NOT in the spec (extra/bonus endpoints). These are not
     * penalised but are noted for transparency.
     */
    @Builder.Default private List<String> extra = List.of();

    /** Percentage of required endpoints that are implemented: 0–100. */
    private int coveragePercent;
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Convenience helpers
  // ──────────────────────────────────────────────────────────────────────────

  /** True if spec adherence is ≥ 90 (all critical requirements implemented). */
  public boolean isHighAccuracy() {
    return specAdherenceScore >= 90;
  }

  /** True if spec adherence is < 70 (significant gaps in implementation). */
  public boolean hasSignificantGaps() {
    return specAdherenceScore < 70;
  }

  /**
   * Total count of unimplemented or partial requirements. UNKNOWN entries are intentionally NOT
   * counted — absence of evidence is not evidence of absence.
   */
  public int incompleteRequirementsCount() {
    int missing = this.missingRequirements != null ? this.missingRequirements.size() : 0;
    int partial = this.partialRequirements != null ? this.partialRequirements.size() : 0;
    return missing + partial;
  }

  /** Count of requirements where the assessor had no evidence either way. Advisory only. */
  public int unknownRequirementsCount() {
    return this.unknownRequirements != null ? this.unknownRequirements.size() : 0;
  }

  /** Returns a one-line summary suitable for SSE/log output. */
  public String toOneLiner() {
    int unknownCount = this.unknownRequirements != null ? this.unknownRequirements.size() : 0;
    return String.format(
        "Accuracy: %s (%d/100) — %d req implemented, %d missing, %d partial%s%s",
        overallAccuracyGrade != null ? overallAccuracyGrade : "?",
        specAdherenceScore,
        implementedRequirements != null ? implementedRequirements.size() : 0,
        missingRequirements != null ? missingRequirements.size() : 0,
        partialRequirements != null ? partialRequirements.size() : 0,
        unknownCount > 0 ? String.format(", %d unknown", unknownCount) : "",
        endpointCoverage != null
            ? String.format(", endpoints=%d%%", endpointCoverage.getCoveragePercent())
            : "");
  }
}
