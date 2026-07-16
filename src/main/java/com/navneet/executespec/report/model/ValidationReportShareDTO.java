package com.navneet.executespec.report.model;

import java.time.Instant;
import java.util.List;

import com.navneet.executespec.report.entity.PipelineResult;

public record ValidationReportShareDTO(
    int filesGenerated,
    List<String> languages,
    PipelineResult pipelineResult,
    List<ReportIssueDTO> issues,
    List<String> checksPassed,
    String reportMarkdown,
    Instant createdAt,
    Integer verifiedScore,
    Integer rubricScore,
    Boolean finalBuildPassed,
    Integer testsPassed,
    Integer testsTotal,
    Boolean executableTestsRun,
    Boolean executableTestsPassed,
    String executableVerificationStatus,
    String executionFailureStage,
    String reviewerVerdict,
    List<VerificationEvidenceDTO> verificationLedger) {}
