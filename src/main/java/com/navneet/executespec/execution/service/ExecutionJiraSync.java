package com.navneet.executespec.execution.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.entity.ExecutionSolution;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Synchronises Execution View solutions with the org's JIRA integration. Uses enterprise-level
 * credentials from {@link Org#getJiraBaseUrl()} / {@code jira_email} / {@code jira_api_token} /
 * {@code jira_project_key}.
 *
 * <p>Callers are responsible for passing the containing {@link ExecutionRun} so we can check {@link
 * ExecutionRun#isJiraEnabled()} before any network call. All methods swallow their own exceptions —
 * JIRA sync must never fail a run.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionJiraSync {

  private final OrgRepository orgRepo;
  private final RestTemplate integrationRestTemplate;

  /** Create a JIRA ticket for a solution; returns the issue key or null. */
  public String createTicket(ExecutionRun run, ExecutionSolution solution) {
    Org org = resolveOrg(run);
    if (org == null) return null;
    try {
      String url = org.getJiraBaseUrl() + "/rest/api/3/issue";
      Map<String, Object> body =
          Map.of(
              "fields",
              Map.of(
                  "project", Map.of("key", org.getJiraProjectKey()),
                  "summary",
                      "["
                          + shortId(run.getId())
                          + "] "
                          + "S"
                          + solution.getSequenceNumber()
                          + " · "
                          + solution.getTitle(),
                  "description", asAdf(buildDescription(run, solution)),
                  "issuetype", Map.of("name", "Task")));
      ResponseEntity<Map> resp =
          integrationRestTemplate.exchange(url, HttpMethod.POST, authEntity(org, body), Map.class);
      Object key = resp.getBody() == null ? null : resp.getBody().get("key");
      if (key == null) return null;
      log.info("ExecutionJiraSync: created JIRA {} for solution {}", key, solution.getId());
      return key.toString();
    } catch (Exception e) {
      log.warn("JIRA createTicket failed for solution {}: {}", solution.getId(), e.getMessage());
      return null;
    }
  }

  /** Comment + optional transition on a solution's JIRA ticket. */
  public void updateTicket(
      ExecutionRun run, ExecutionSolution solution, String comment, String targetStatusName) {
    if (solution.getJiraTicketId() == null || solution.getJiraTicketId().isBlank()) return;
    Org org = resolveOrg(run);
    if (org == null) return;

    if (comment != null && !comment.isBlank()) {
      try {
        String url =
            org.getJiraBaseUrl() + "/rest/api/3/issue/" + solution.getJiraTicketId() + "/comment";
        integrationRestTemplate.exchange(
            url, HttpMethod.POST, authEntity(org, Map.of("body", asAdf(comment))), Map.class);
      } catch (Exception e) {
        log.warn("JIRA addComment failed for {}: {}", solution.getJiraTicketId(), e.getMessage());
      }
    }

    if (targetStatusName == null || targetStatusName.isBlank()) return;
    try {
      String url =
          org.getJiraBaseUrl() + "/rest/api/3/issue/" + solution.getJiraTicketId() + "/transitions";
      ResponseEntity<Map> tr =
          integrationRestTemplate.exchange(
              url + "?expand=transitions", HttpMethod.GET, authEntity(org, null), Map.class);
      if (tr.getBody() == null) return;
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> transitions =
          (List<Map<String, Object>>) tr.getBody().get("transitions");
      if (transitions == null) return;
      String transitionId =
          transitions.stream()
              .filter(
                  t -> {
                    Object to = t.get("to");
                    if (!(to instanceof Map<?, ?> m)) return false;
                    return targetStatusName.equalsIgnoreCase(String.valueOf(m.get("name")));
                  })
              .map(t -> String.valueOf(t.get("id")))
              .findFirst()
              .orElse(null);
      if (transitionId == null) {
        log.warn(
            "JIRA transition to '{}' not available for {}",
            targetStatusName,
            solution.getJiraTicketId());
        return;
      }
      integrationRestTemplate.exchange(
          url,
          HttpMethod.POST,
          authEntity(org, Map.of("transition", Map.of("id", transitionId))),
          Map.class);
    } catch (Exception e) {
      log.warn("JIRA transition failed for {}: {}", solution.getJiraTicketId(), e.getMessage());
    }
  }

  private Org resolveOrg(ExecutionRun run) {
    if (!run.isJiraEnabled()) return null;
    Org org = orgRepo.findById(run.getOrgId()).orElse(null);
    if (org == null) return null;
    if (org.getJiraBaseUrl() == null
        || org.getJiraEmail() == null
        || org.getJiraApiToken() == null
        || org.getJiraProjectKey() == null) {
      log.debug("Org {} missing JIRA creds / project key — skipping sync", org.getId());
      return null;
    }
    return org;
  }

  private <T> HttpEntity<T> authEntity(Org org, T body) {
    HttpHeaders h = new HttpHeaders();
    h.setContentType(MediaType.APPLICATION_JSON);
    h.set("Accept", "application/json");
    String creds = org.getJiraEmail() + ":" + org.getJiraApiToken();
    h.set(
        "Authorization",
        "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8)));
    return new HttpEntity<>(body, h);
  }

  private String buildDescription(ExecutionRun run, ExecutionSolution s) {
    StringBuilder sb = new StringBuilder();
    sb.append("Execution: ").append(shortId(run.getId())).append("\n\n");
    sb.append("Project: ").append(run.getProjectId()).append("\n");
    sb.append("Complexity: ")
        .append(s.getComplexity() == null ? "—" : s.getComplexity())
        .append("\n\n");
    if (s.getDescription() != null) sb.append(s.getDescription()).append("\n\n");
    sb.append("Tracked by ExecuteSpec Execution View.");
    return sb.toString();
  }

  /**
   * JIRA REST API v3 requires Atlassian Document Format. Wrap plain text in the minimal paragraph
   * structure so plain strings still render correctly.
   */
  private Map<String, Object> asAdf(String text) {
    return Map.of(
        "type",
        "doc",
        "version",
        1,
        "content",
        List.of(
            Map.of(
                "type",
                "paragraph",
                "content",
                List.of(Map.of("type", "text", "text", text == null ? "" : text)))));
  }

  private String shortId(String id) {
    return id == null ? "" : id.substring(0, Math.min(8, id.length()));
  }
}
