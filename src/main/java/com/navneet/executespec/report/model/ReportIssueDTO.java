package com.navneet.executespec.report.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReportIssueDTO(
    String description, String severity, String fix, String stage, String filePath) {}
