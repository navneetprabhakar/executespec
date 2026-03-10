package com.navneet.executespec.reviewer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReviewIssue {
    private String id;              // unique issue ID e.g. "REV-001"
    private IssueSeverity severity; // CRITICAL or MINOR
    private IssueCategory category;
    private String file;            // affected file path
    private String description;     // what's wrong
    private String fixInstruction;  // how to fix it (for CRITICAL, sent to worker)
    private boolean autoFixed;      // was this auto-fixed?
    private boolean fixSucceeded;   // did the auto-fix pass validation?
}
