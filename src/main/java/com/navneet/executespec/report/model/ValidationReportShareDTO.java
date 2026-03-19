package com.navneet.executespec.report.model;

import com.navneet.executespec.report.entity.PipelineResult;

import java.time.Instant;
import java.util.List;

public record ValidationReportShareDTO(
    int filesGenerated,
    List<String> languages,
    PipelineResult pipelineResult,
    List<ReportIssueDTO> issues,
    List<String> checksPassed,
    String reportMarkdown,
    Instant createdAt
) {}
