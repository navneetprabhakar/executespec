package com.navneet.agentteam.reviewer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.util.List;

/**
 * Review report produced by {@link com.navneet.agentteam.reviewer.ReviewerAgentV2}.
 * Separate from {@link com.navneet.agentteam.agent.model.ReviewReport} (v1) which
 * uses recommendation-based flow (MERGE/REWORK/PARTIAL_MERGE).
 *
 * <p>V2 uses verdict-based flow (APPROVED/APPROVED_WITH_NOTES/NEEDS_FIXES/REJECTED)
 * with built-in auto-fix loop for CRITICAL issues.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReviewReportV2 {
    private ReviewVerdict verdict;       // APPROVED, APPROVED_WITH_NOTES, NEEDS_FIXES, REJECTED
    private List<ReviewIssue> issues;    // all issues found
    private int criticalCount;
    private int minorCount;
    private int autoFixAttempted;
    private int autoFixSucceeded;
    private String summary;              // human-readable summary paragraph
    private Duration reviewDuration;
    private String reviewerModel;        // which model did the review
    private boolean readmeUpdated;       // hard gate check
}
