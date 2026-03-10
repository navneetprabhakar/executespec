package com.navneet.executespec.integration.confluence;

import com.navneet.executespec.integration.IntegrationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
public class ConfluenceClient {

    private final RestTemplate restTemplate;
    private final IntegrationProperties.ConfluenceConfig config;

    public ConfluenceClient(RestTemplate restTemplate, IntegrationProperties.ConfluenceConfig config) {
        this.restTemplate = restTemplate;
        this.config = config;
    }

    /** Fetch page content as plain text (strips HTML tags, preserves structure). */
    public String fetchPageContent(String pageId) {
        String url = config.getBaseUrl() + "/wiki/rest/api/content/" + pageId +
                     "?expand=body.storage,version,space";
        try {
            ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.GET,
                    authenticatedRequest(null), Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) resp.getBody().get("body");
            @SuppressWarnings("unchecked")
            Map<String, Object> storage = (Map<String, Object>) body.get("storage");
            String html = String.valueOf(storage.get("value"));
            return stripHtml(html);
        } catch (Exception e) {
            log.error("[Confluence] Failed to fetch page {}: {}", pageId, e.getMessage());
            throw e;
        }
    }

    /** Create a child page under parentPageId in the given space. */
    public void createChildPage(String parentPageId, String spaceKey, String title, String content) {
        String url = config.getBaseUrl() + "/wiki/rest/api/content";
        Map<String, Object> payload = Map.of(
            "type", "page",
            "title", title,
            "space", Map.of("key", spaceKey),
            "ancestors", List.of(Map.of("id", parentPageId)),
            "body", Map.of(
                "storage", Map.of(
                    "value", markdownToConfluenceStorage(content),
                    "representation", "storage"
                )
            )
        );
        try {
            restTemplate.postForObject(url, authenticatedRequest(payload), String.class);
            log.info("[Confluence] Created child page '{}' under parent {}", title, parentPageId);
        } catch (Exception e) {
            log.error("[Confluence] Failed to create child page '{}': {}", title, e.getMessage());
            throw e;
        }
    }

    private String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", "")
                   .replaceAll("&amp;", "&")
                   .replaceAll("&lt;", "<")
                   .replaceAll("&gt;", ">")
                   .replaceAll("&nbsp;", " ")
                   .trim();
    }

    private String markdownToConfluenceStorage(String md) {
        if (md == null) return "";
        // Minimal conversion: wrap in Confluence XHTML paragraph blocks
        return "<p>" + md.replace("\n\n", "</p><p>").replace("\n", "<br/>") + "</p>";
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
