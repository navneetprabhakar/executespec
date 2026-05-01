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
    // Preview URL at time of report generation (nullable — batch runs and expired previews won't
    // have this)
    String previewUrl) {}
