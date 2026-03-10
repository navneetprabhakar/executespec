package com.navneet.executespec.integration;

/**
 * Normalised inbound event produced by every InboundAdapter.
 * Carries everything PipelineHarness needs to submit a run.
 */
public record IntegrationEvent(
    String sourceType,          // "JIRA" | "CONFLUENCE" | "SERVICENOW" | "PAGERDUTY"
    String sourceId,            // issue key, page ID, incident sysId, alert id
    String sourceUrl,           // direct link for outbound references
    String goal,                // normalised goal sentence for PlannerAgent
    String contextMarkdown,     // injected as additional context into the run request
    String acceptanceCriteria,  // comma-separated or markdown list
    boolean skipApproval        // from per-integration config
) {}
