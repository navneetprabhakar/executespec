package com.navneet.executespec.integration.servicenow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ServiceNowWebhookPayload(
    String eventId,
    ServiceNowRecord record
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServiceNowRecord(
        @JsonProperty("sys_id") String sysId,
        String number,                              // INC0012345
        @JsonProperty("short_description") String shortDescription,
        String description,
        String category,
        String state,
        @JsonProperty("kb_article") String kbArticle  // optional linked runbook
    ) {}
}
