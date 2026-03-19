package com.navneet.executespec.report.model;

import com.navneet.executespec.report.entity.PipelineResult;

import java.time.Instant;
import java.util.List;

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
    String shareUrl
) {}
