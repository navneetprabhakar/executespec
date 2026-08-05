package com.navneet.executespec.report.model;

import java.time.Instant;
import java.util.List;

import com.navneet.executespec.execution.model.GoalDonenessDecision;
import com.navneet.executespec.report.entity.PipelineResult;

public record ValidationReportDTO(
    String id,
    String runId,
    String workspaceGeneration,
    String reportStatus,
    int filesGenerated,
    List<String> languages,
    PipelineResult pipelineResult,
    List<ReportIssueDTO> issues,
    List<String> checksPassed,
    String reportMarkdown,
    /**
     * Legacy single-model field. Historically the model that generated the report markdown; it was
     * widely read as "the model that served the run", which is untrue for multi-route runs. When
     * the run's physical attribution is {@code MIXED} this field now carries the literal {@code
     * "MIXED"} instead of an arbitrary single model — consumers needing the full set must read
     * {@link #modelsUsed()} and {@link #routeAttribution()}.
     */
    String modelUsed,
    Instant createdAt,
    int issuesCaught,
    int buildBreakers,
    int reviewFailures,
    int productionBugs,
    int lintFailures,
    boolean isShared,
    String shareUrl,
    boolean generatedAfterImprovements,
    // Phase 3: Accuracy assessment fields (nullable — older runs won't have these)
    Integer accuracyScore, // 0-100 spec adherence score
    String accuracyGrade, // A/B/C/D/F
    String accuracyJson, // full AccuracyReport JSON for frontend detail view
    // Pilot R6 — multi-dimension scores (nullable for runs before V83).
    Integer codeQualityScore,
    Integer testCoverageScore,
    Integer architectureScore,
    Integer securityScore,
    Integer performanceScore,
    Integer designComplianceScore,
    // v2.5.10 (V106) — verified signals. Populated on new runs once the
    // scoring.v2-verified flag is on; null on historical rows.
    Boolean finalBuildPassed,
    Integer testsPassed,
    Integer testsTotal,
    Integer testsSkipped,
    String reviewerVerdict, // CLEAN | FIXED_ISSUES | UNRESOLVED_ISSUES | REVIEW_FAILED
    Integer verifiedScore, // 0-100 deterministic score; primary UI badge
    /**
     * RL-2026-06 — auditable evidence ledger behind the headline score. The score remains the
     * operational routing number; this ledger explains which claims are deterministic, LLM-derived,
     * or unmeasured.
     */
    List<VerificationEvidenceDTO> verificationLedger,
    /**
     * Conservative run-level proof derived from the final validation ledger. `STOP_DONE` is emitted
     * only when the report result and required proof rows pass; missing or warning evidence remains
     * UNCERTAIN instead of letting the internal score vouch for completion.
     */
    GoalDonenessDecision runGoalDecision,
    /**
     * Typed references to the exact ledger rows used by {@link #runGoalDecision}. `proofStatus`
     * distinguishes proven, blocking, unproven, and contextual rows without parsing reason text.
     */
    List<GoalProofEvidenceReferenceDTO> goalProofReferences,
    /**
     * PR-E Phase A (2026-05-13, V194) — additive rubric score 0-100. Each dimension contributes
     * points independently so a single zero factor doesn't collapse the headline number the way the
     * multiplicative {@link #verifiedScore} does. UI picks the more informative of the two per run
     * (typically: rubricScore when verifiedScore < rubricScore by ≥20 points). Null on pre-V194
     * rows.
     */
    Integer rubricScore,
    // Preview URL at time of report generation (nullable — batch runs and expired previews won't
    // have this)
    String previewUrl,
    /**
     * Additive: distinct models proven by the run's successful physical-attempt ledger, first-seen
     * order (via {@code RunEffectiveRouteService}). Empty when no successful attempt names a real
     * model or the ledger seam is unavailable.
     */
    List<String> modelsUsed,
    /**
     * Additive: {@code LEDGER} (exactly one successful route), {@code MIXED} (several), or {@code
     * UNATTRIBUTED} (no successful attempt). {@code null} only when the ledger seam was unavailable
     * to the projection (bare test constructions).
     */
    String routeAttribution) {}
