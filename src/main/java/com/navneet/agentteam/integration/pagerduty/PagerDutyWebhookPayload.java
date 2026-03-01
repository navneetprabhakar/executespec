package com.navneet.agentteam.integration.pagerduty;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PagerDutyWebhookPayload(
    List<PdMessage> messages
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PdMessage(
        String id,          // unique event ID for idempotency
        String type,        // "incident.trigger", "incident.acknowledge"
        PdIncident incident
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PdIncident(
        String id,
        @JsonProperty("incident_number") String incidentNumber,
        String title,
        String status,
        @JsonProperty("html_url") String htmlUrl,
        PdService service,
        @JsonProperty("first_trigger_log_entry") PdLogEntry firstTriggerLogEntry
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PdService(
        String id,
        String name,
        String description
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PdLogEntry(PdChannel channel) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PdChannel(Map<String, Object> details) {}
}
