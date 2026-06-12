package com.navneet.executespec.report.model;

import java.time.Instant;
import java.util.List;

import com.navneet.executespec.report.entity.PipelineResult;

public record ValidationReportDTO(
    String id,
    String runId,
    int filesGenerated,
    List<String> languages,
    PipelineResult pipelineResult,
    List<ReportIssueDTO> issues,
    List<String> checksPassed,
    String reportMarkdown,
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
    String reviewerVerdict, // CLEAN | FIXED_ISSUES | UNRESOLVED_ISSUES | REVIEW_FAILED
    Integer verifiedScore, // 0-100 deterministic score; primary UI badge
    /**
     * RL-2026-06 — auditable evidence ledger behind the headline score. The score remains the
     * operational routing number; this ledger explains which claims are deterministic, LLM-derived,
     * or unmeasured.
     */
    List<VerificationEvidenceDTO> verificationLedger,
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
    String previewUrl) {}
