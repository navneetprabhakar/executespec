package com.navneet.executespec.integration.bitbucket;

import com.navneet.executespec.integration.IntegrationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.Map;

@Slf4j
public class BitbucketClient {

    private static final String API_BASE = "https://api.bitbucket.org/2.0";

    private final RestTemplate restTemplate;
    private final IntegrationProperties.BitbucketConfig config;

    public BitbucketClient(RestTemplate restTemplate, IntegrationProperties.BitbucketConfig config) {
        this.restTemplate = restTemplate;
        this.config = config;
    }

    /**
     * Post a comment on a Bitbucket pull request.
     */
    public void postPrComment(String workspace, String repoSlug, int prId,
                              String comment, String username, String appPassword) {
        String url = API_BASE + "/repositories/" + workspace + "/" + repoSlug
                + "/pullrequests/" + prId + "/comments";
        Map<String, Object> body = Map.of("content", Map.of("raw", comment));
        try {
            restTemplate.postForObject(url, authenticatedRequest(body, username, appPassword), String.class);
            log.info("[Bitbucket] Posted comment on PR {}/{}/{}#{}", workspace, repoSlug, prId, prId);
        } catch (Exception e) {
            log.error("[Bitbucket] Failed to post comment on PR {}/{}/{}#{}: {}",
                    workspace, repoSlug, prId, prId, e.getMessage());
            throw e;
        }
    }

    /**
     * Fetch pull request details from Bitbucket.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getPullRequest(String workspace, String repoSlug, int prId,
                                              String username, String appPassword) {
        String url = API_BASE + "/repositories/" + workspace + "/" + repoSlug
                + "/pullrequests/" + prId;
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.GET, authenticatedRequest(null, username, appPassword), Map.class);
            log.info("[Bitbucket] Fetched PR {}/{}/{}#{}", workspace, repoSlug, prId, prId);
            return resp.getBody();
        } catch (Exception e) {
            log.error("[Bitbucket] Failed to fetch PR {}/{}/{}#{}: {}",
                    workspace, repoSlug, prId, prId, e.getMessage());
            throw e;
        }
    }

    private <T> HttpEntity<T> authenticatedRequest(T body, String username, String appPassword) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String encoded = Base64.getEncoder().encodeToString(
                (username + ":" + appPassword).getBytes());
        headers.set("Authorization", "Basic " + encoded);
        return new HttpEntity<>(body, headers);
    }
}
