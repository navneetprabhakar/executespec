package com.navneet.executespec.reviewer.model;

/**
 * Categories of review issues. The reviewer tags each issue with one.
 * Helps the human quickly triage the review report.
 */
public enum IssueCategory {
    SPEC_MISMATCH,        // code doesn't match what was requested
    MISSING_ENDPOINT,     // API endpoint in spec but not implemented
    MISSING_FEATURE,      // feature described in spec but absent
    LOGIC_ERROR,          // compiles but semantically wrong
    SECURITY,             // hardcoded secrets, injection vectors, missing input validation
    ERROR_HANDLING,       // missing try-catch, null checks on external calls
    CROSS_TASK_MISMATCH,  // controller expects X but service returns Y
    README_INCOMPLETE,    // README missing or doesn't reflect actual code
    CODE_QUALITY,         // style, naming, structure (always MINOR)
    COMPLETENESS          // missing tests, missing docs, incomplete edge cases
}
