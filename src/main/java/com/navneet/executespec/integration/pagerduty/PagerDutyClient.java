package com.navneet.executespec.integration.pagerduty;

import com.navneet.executespec.integration.IntegrationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Slf4j
public class PagerDutyClient {

    private static final String PD_BASE = "https://api.pagerduty.com";

    private final RestTemplate restTemplate;
    private final IntegrationProperties.PagerDutyConfig config;

    public PagerDutyClient(RestTemplate restTemplate, IntegrationProperties.PagerDutyConfig config) {
        this.restTemplate = restTemplate;
        this.config = config;
    }

    /** Add a responder note to a PagerDuty incident. */
    public void addNote(String incidentId, String content) {
        String url = PD_BASE + "/incidents/" + incidentId + "/notes";
        Map<String, Object> payload = Map.of("note", Map.of("content", content));
        try {
            restTemplate.exchange(url, HttpMethod.POST, authenticatedRequest(payload), String.class);
            log.info("[PagerDuty] Added note to incident {}", incidentId);
        } catch (Exception e) {
            log.error("[PagerDuty] Failed to add note to incident {}: {}", incidentId, e.getMessage());
            throw e;
        }
    }

    /** Resolve a PagerDuty incident via API. Requires From email header. */
    public void resolveIncident(String incidentId) {
        String url = PD_BASE + "/incidents/" + incidentId;
        Map<String, Object> payload = Map.of(
            "incident", Map.of("type", "incident_reference", "status", "resolved")
        );
        HttpHeaders headers = buildHeaders();
        if (config.getFromEmail() != null) {
            headers.set("From", config.getFromEmail());
        }
        try {
            restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(payload, headers), String.class);
            log.info("[PagerDuty] Resolved incident {}", incidentId);
        } catch (Exception e) {
            log.error("[PagerDuty] Failed to resolve incident {}: {}", incidentId, e.getMessage());
            throw e;
        }
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("Authorization", "Token token=" + config.getApiToken());
        return headers;
    }

    private <T> HttpEntity<T> authenticatedRequest(T body) {
        return new HttpEntity<>(body, buildHeaders());
    }
}
