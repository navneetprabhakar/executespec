package com.navneet.executespec.integration.slack;

import java.util.List;
import java.util.Map;

import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import lombok.extern.slf4j.Slf4j;

/**
 * Low-level HTTP client for the Slack Web API. Uses the shared {@code integrationRestTemplate} (10
 * s connect / 30 s read timeout).
 */
@Slf4j
public class SlackClient {

  private static final String SLACK_API = "https://slack.com/api";
  private final RestTemplate restTemplate;

  public SlackClient(RestTemplate restTemplate) {
    this.restTemplate = restTemplate;
  }

  // ── Chat ──────────────────────────────────────────────────────────────────

  /**
   * Posts a Block Kit message to a Slack channel.
   *
   * @return the full Slack response (includes "ok", "ts", "channel", etc.)
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> chatPostMessage(
      String botToken,
      String channelId,
      List<Map<String, Object>> blocks,
      List<Map<String, Object>> attachments) {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("channel", channelId);
    if (blocks != null && !blocks.isEmpty()) body.put("blocks", blocks);
    if (attachments != null && !attachments.isEmpty()) body.put("attachments", attachments);
    body.put("unfurl_links", false);
    body.put("unfurl_media", false);

    ResponseEntity<Map> resp =
        restTemplate.exchange(
            SLACK_API + "/chat.postMessage",
            HttpMethod.POST,
            bearerEntity(botToken, body),
            Map.class);

    Map<String, Object> result = resp.getBody();
    if (result != null && !Boolean.TRUE.equals(result.get("ok"))) {
      log.warn("[Slack] chat.postMessage failed: {}", result.get("error"));
    }
    return result != null ? result : Map.of();
  }

  // ── OAuth ─────────────────────────────────────────────────────────────────

  /** Exchanges an OAuth V2 authorization code for a bot access token. */
  @SuppressWarnings("unchecked")
  public Map<String, Object> oauthV2Access(
      String clientId, String clientSecret, String code, String redirectUri) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

    String formBody =
        "client_id="
            + clientId
            + "&client_secret="
            + clientSecret
            + "&code="
            + code
            + "&redirect_uri="
            + redirectUri;

    HttpEntity<String> entity = new HttpEntity<>(formBody, headers);
    ResponseEntity<Map> resp =
        restTemplate.exchange(SLACK_API + "/oauth.v2.access", HttpMethod.POST, entity, Map.class);

    Map<String, Object> result = resp.getBody();
    if (result != null && !Boolean.TRUE.equals(result.get("ok"))) {
      log.error("[Slack] oauth.v2.access failed: {}", result.get("error"));
    }
    return result != null ? result : Map.of();
  }

  // ── Auth test ─────────────────────────────────────────────────────────────

  /** Validates a bot token and returns workspace/bot info. */
  @SuppressWarnings("unchecked")
  public Map<String, Object> authTest(String botToken) {
    ResponseEntity<Map> resp =
        restTemplate.exchange(
            SLACK_API + "/auth.test", HttpMethod.POST, bearerEntity(botToken, Map.of()), Map.class);
    return resp.getBody() != null ? resp.getBody() : Map.of();
  }

  // ── Conversations ─────────────────────────────────────────────────────────

  /** Lists Slack channels the bot can post to. */
  @SuppressWarnings("unchecked")
  public Map<String, Object> conversationsList(String botToken, String cursor, int limit) {
    String url =
        SLACK_API
            + "/conversations.list?types=public_channel,private_channel"
            + "&exclude_archived=true&limit="
            + limit;
    if (cursor != null && !cursor.isBlank()) url += "&cursor=" + cursor;

    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(botToken);
    ResponseEntity<Map> resp =
        restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    return resp.getBody() != null ? resp.getBody() : Map.of();
  }

  // ── Token revocation ──────────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  public void authRevoke(String botToken) {
    try {
      restTemplate.exchange(
          SLACK_API + "/auth.revoke", HttpMethod.POST, bearerEntity(botToken, Map.of()), Map.class);
    } catch (Exception e) {
      log.warn("[Slack] Token revocation failed (may already be revoked): {}", e.getMessage());
    }
  }

  // ── Private helpers ───────────────────────────────────────────────────────

  private <T> HttpEntity<T> bearerEntity(String botToken, T body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(botToken);
    return new HttpEntity<>(body, headers);
  }
}
