package com.navneet.executespec.testgen.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestReportDto {
    private Long testPlanId;
    private String runId;

    private int totalTests;
    private int passed;
    private int failed;
    private int skipped;
    private int errors;

    private List<TestResultDetail> results;
    private List<FailureSuggestion> suggestions;

    private long executionDurationMs;
    private String previewUrl;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestResultDetail {
        private Long testResultId;
        private String testClass;
        private String testMethod;
        private String status;
        private String failureMessage;
        private String expected;
        private String actual;
        private int durationMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailureSuggestion {
        private String testClass;
        private String testMethod;
        private String failureMessage;
        private String suggestion;
    }
}
