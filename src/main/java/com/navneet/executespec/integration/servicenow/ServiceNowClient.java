package com.navneet.executespec.integration.servicenow;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.navneet.executespec.integration.IntegrationProperties;

import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ServiceNowClient {

  /**
   * RR-647 — ServiceNow KB article numbers are the fixed form {@code KB} followed by digits (e.g.
   * {@code KB0010001}). The {@code kbArticle} field originates from incident text a user can
   * author, so it must be allowlisted to this exact grammar before it is placed into an encoded
   * ServiceNow query — otherwise appended query operators (e.g. {@code KB1^ORnumberISNOTEMPTY})
   * would let the integration identity read a different KB row whose text is then injected into the
   * LLM context.
   */
  private static final Pattern KB_ARTICLE_NUMBER = Pattern.compile("KB\\d{1,20}");

  private final RestTemplate restTemplate;
  private final IntegrationProperties.ServiceNowConfig config;

  public ServiceNowClient(
      RestTemplate restTemplate, IntegrationProperties.ServiceNowConfig config) {
    this.restTemplate = restTemplate;
    this.config = config;
  }

  /** Add a work note to an incident by sysId. */
  public void addWorkNote(String sysId, String note) {
    String url = config.getBaseUrl() + "/api/now/table/incident/" + sysId;
    try {
      restTemplate.exchange(
          url, HttpMethod.PATCH, authenticatedRequest(Map.of("work_notes", note)), String.class);
      log.info("[ServiceNow] Added work note to incident {}", sysId);
    } catch (Exception e) {
      log.error("[ServiceNow] Failed to add work note to incident {}: {}", sysId, e.getMessage());
      throw e;
    }
  }

  /** Fetch KB article body by article number (for runbook context injection). */
  public String fetchKbArticle(String articleNumber) {
    // RR-647 — reject anything that is not the exact KB-number grammar before it reaches the
    // encoded ServiceNow query. This blocks query-language injection through incident-controlled
    // text; the query value is additionally URI-encoded exactly once via UriComponentsBuilder.
    if (articleNumber == null || !KB_ARTICLE_NUMBER.matcher(articleNumber.trim()).matches()) {
      log.warn(
          "[ServiceNow] Refusing to fetch KB article — value is not a valid KB number: {}",
          articleNumber);
      return "";
    }
    String kbNumber = articleNumber.trim();
    String url =
        UriComponentsBuilder.fromHttpUrl(config.getBaseUrl())
            .path("/api/now/table/kb_knowledge")
            .queryParam("sysparm_query", "number=" + kbNumber)
            .queryParam("sysparm_fields", "number,text")
            .queryParam("sysparm_limit", "1")
            .encode(StandardCharsets.UTF_8)
            .toUriString();
    try {
      ResponseEntity<Map> resp =
          restTemplate.exchange(url, HttpMethod.GET, authenticatedRequest(null), Map.class);
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) resp.getBody().get("result");
      if (results == null || results.isEmpty()) return "";
      Map<String, Object> row = results.get(0);
      // RR-647 — verify the returned record's number matches exactly; a query that resolved to a
      // different row (e.g. via an operator that slipped past the grammar) must not be injected.
      Object returnedNumber = row.get("number");
      if (returnedNumber == null || !kbNumber.equals(String.valueOf(returnedNumber))) {
        log.warn(
            "[ServiceNow] KB article number mismatch (requested {}, returned {}) — discarding",
            kbNumber,
            returnedNumber);
        return "";
      }
      Object text = row.get("text");
      return text != null ? String.valueOf(text) : "";
    } catch (Exception e) {
      log.warn("[ServiceNow] Could not fetch KB article {}: {}", kbNumber, e.getMessage());
      return "";
    }
  }

  /** Optionally resolve the incident (state = 6 = "Resolved" in default ServiceNow). */
  public void resolveIncident(String sysId, String resolution) {
    String url = config.getBaseUrl() + "/api/now/table/incident/" + sysId;
    try {
      restTemplate.exchange(
          url,
          HttpMethod.PATCH,
          authenticatedRequest(
              Map.of(
                  "state", "6",
                  "close_code", "Solved (Permanently)",
                  "close_notes", resolution)),
          String.class);
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
