package com.navneet.agentteam.integration.confluence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfluenceWebhookPayload(
    String event,       // "page_created" | "page_updated"
    ConfluencePage page
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfluencePage(
        String id,
        String title,
        String spaceKey,
        List<ConfluenceLabel> labels
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConfluenceLabel(
        String name,
        String prefix
    ) {}
}
