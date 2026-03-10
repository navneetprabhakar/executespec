package com.navneet.executespec.integration.jira;

import com.navneet.executespec.integration.IntegrationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
public class JiraClient {

    private final RestTemplate restTemplate;
    private final IntegrationProperties.JiraConfig config;

    public JiraClient(RestTemplate restTemplate, IntegrationProperties.JiraConfig config) {
        this.restTemplate = restTemplate;
        this.config = config;
    }

    /**
     * Fetch a Jira issue by key. Returns the raw response map
     * containing fields: summary, description, status, acceptanceCriteria.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchIssue(String issueKey) {
        String url = config.getBaseUrl() + "/rest/api/3/issue/" + issueKey
                + "?fields=summary,description,status,customfield_10034";
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.GET, authenticatedRequest(null), Map.class);
            log.info("[Jira] Fetched issue {}", issueKey);
            return resp.getBody();
        } catch (Exception e) {
            log.error("[Jira] Failed to fetch issue {}: {}", issueKey, e.getMessage());
            throw e;
        }
    }

    /** Add a plain-text comment to a Jira issue (Atlassian Document Format v3). */
    public void addComment(String issueKey, String commentBody) {
        String url = config.getBaseUrl() + "/rest/api/3/issue/" + issueKey + "/comment";
        Map<String, Object> body = Map.of(
            "body", Map.of(
                "type", "doc",
                "version", 1,
                "content", List.of(Map.of(
                    "type", "paragraph",
                    "content", List.of(Map.of("type", "text", "text", commentBody))
                ))
            )
        );
        try {
            restTemplate.postForObject(url, authenticatedRequest(body), String.class);
            log.info("[Jira] Added comment to {}", issueKey);
        } catch (Exception e) {
            log.error("[Jira] Failed to add comment to {}: {}", issueKey, e.getMessage());
            throw e;
        }
    }

    /** Transition a Jira issue to a target status by name. */
    public void transitionIssue(String issueKey, String targetStatusName) {
        String transUrl = config.getBaseUrl() + "/rest/api/3/issue/" + issueKey + "/transitions";
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    transUrl, HttpMethod.GET, authenticatedRequest(null), Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> transitionList =
                    (List<Map<String, Object>>) resp.getBody().get("transitions");

            String transitionId = transitionList.stream()
                    .filter(t -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> to = (Map<String, Object>) t.get("to");
                        return targetStatusName.equalsIgnoreCase(String.valueOf(to.get("name")));
                    })
                    .findFirst()
                    .map(t -> String.valueOf(t.get("id")))
                    .orElseThrow(() -> new IllegalStateException(
                            "No transition to status: " + targetStatusName + " for " + issueKey));

            restTemplate.postForObject(transUrl,
                    authenticatedRequest(Map.of("transition", Map.of("id", transitionId))),
                    Void.class);
            log.info("[Jira] Transitioned {} to {}", issueKey, targetStatusName);
        } catch (Exception e) {
            log.error("[Jira] Failed to transition {} to {}: {}", issueKey, targetStatusName, e.getMessage());
            throw e;
        }
    }

    private <T> HttpEntity<T> authenticatedRequest(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String encoded = Base64.getEncoder().encodeToString(
                (config.getEmail() + ":" + config.getApiToken()).getBytes());
        headers.set("Authorization", "Basic " + encoded);
        return new HttpEntity<>(body, headers);
    }
}
