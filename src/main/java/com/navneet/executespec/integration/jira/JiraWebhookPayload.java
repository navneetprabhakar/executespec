package com.navneet.executespec.integration.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JiraWebhookPayload(
    String webhookEvent,        // "jira:issue_updated"
    String timestamp,
    JiraIssue issue,
    JiraChangelog changelog
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JiraIssue(
        String id,
        String key,
        String self,
        JiraFields fields
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JiraFields(
        JiraStatus status,
        String summary,
        @JsonProperty("description") Object description,       // may be string or ADF object
        @JsonProperty("customfield_10016") String storyPoints,
        @JsonProperty("customfield_10034") String acceptanceCriteria  // field name varies per org
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JiraStatus(String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JiraChangelog(List<JiraChangeItem> items) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JiraChangeItem(
        String field,
        String fromString,
        @JsonProperty("toString") String toStringValue
    ) {}
}
