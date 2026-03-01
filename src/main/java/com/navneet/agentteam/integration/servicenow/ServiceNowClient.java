package com.navneet.agentteam.integration.servicenow;

import com.navneet.agentteam.integration.IntegrationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Slf4j
public class ServiceNowClient {

    private final RestTemplate restTemplate;
    private final IntegrationProperties.ServiceNowConfig config;

    public ServiceNowClient(RestTemplate restTemplate, IntegrationProperties.ServiceNowConfig config) {
        this.restTemplate = restTemplate;
        this.config = config;
    }

    /** Add a work note to an incident by sysId. */
    public void addWorkNote(String sysId, String note) {
        String url = config.getBaseUrl() + "/api/now/table/incident/" + sysId;
        try {
            restTemplate.exchange(url, HttpMethod.PATCH,
                    authenticatedRequest(Map.of("work_notes", note)), String.class);
            log.info("[ServiceNow] Added work note to incident {}", sysId);
        } catch (Exception e) {
            log.error("[ServiceNow] Failed to add work note to incident {}: {}", sysId, e.getMessage());
            throw e;
        }
    }

    /** Fetch KB article body by article number (for runbook context injection). */
    public String fetchKbArticle(String articleNumber) {
        String url = config.getBaseUrl() + "/api/now/table/kb_knowledge" +
                     "?sysparm_query=number=" + articleNumber +
                     "&sysparm_fields=text&sysparm_limit=1";
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.GET,
                    authenticatedRequest(null), Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) resp.getBody().get("result");
            if (results == null || results.isEmpty()) return "";
            Object text = results.get(0).get("text");
            return text != null ? String.valueOf(text) : "";
        } catch (Exception e) {
            log.warn("[ServiceNow] Could not fetch KB article {}: {}", articleNumber, e.getMessage());
            return "";
        }
    }

    /** Optionally resolve the incident (state = 6 = "Resolved" in default ServiceNow). */
    public void resolveIncident(String sysId, String resolution) {
        String url = config.getBaseUrl() + "/api/now/table/incident/" + sysId;
        try {
            restTemplate.exchange(url, HttpMethod.PATCH, authenticatedRequest(Map.of(
                    "state", "6",
                    "close_code", "Solved (Permanently)",
                    "close_notes", resolution
            )), String.class);
            log.info("[ServiceNow] Resolved incident {}", sysId);
        } catch (Exception e) {
            log.error("[ServiceNow] Failed to resolve incident {}: {}", sysId, e.getMessage());
            throw e;
        }
    }

    private <T> HttpEntity<T> authenticatedRequest(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBasicAuth(config.getUsername(), config.getPassword());
        return new HttpEntity<>(body, headers);
    }
}
