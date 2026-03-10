package com.navneet.executespec.reviewer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A targeted fix instruction from the reviewer to be executed by a worker.
 * Contains exactly what's wrong and what the fix should be.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FixInstruction {
    private String issueId;          // references ReviewIssue.id
    private String file;             // file to fix
    private String problem;          // what's wrong (for worker context)
    private String expectedBehavior; // what it should do
    private String suggestedFix;     // how to fix it (guidance, not exact code)
}
