package com.navneet.executespec.integration.slack;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.navneet.executespec.integration.IntegrationProperties;
import com.navneet.executespec.integration.slack.model.OrgSlackIntegration;
import com.navneet.executespec.integration.slack.model.OrgSlackIntegrationRepository;
import com.navneet.executespec.integration.slack.model.ProjectSlackChannelRepository;

import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

/**
 * Handles the Slack OAuth 2.0 V2 bot installation flow.
 *
 * <p>The {@code state} parameter is HMAC-signed (not encrypted) to prevent CSRF: {@code
 * orgId|timestamp|hmac(orgId|timestamp, secret)}.
 */
@Slf4j
public class SlackOAuthService {

  private static final long STATE_TTL_MS = 300_000; // 5 minutes

  private final SlackClient slackClient;
  private final OrgSlackIntegrationRepository integrationRepo;
  private final ProjectSlackChannelRepository channelRepo;
  private final IntegrationProperties.SlackConfig config;
  private final String hmacSecret;

  public SlackOAuthService(
      SlackClient slackClient,
      OrgSlackIntegrationRepository integrationRepo,
      ProjectSlackChannelRepository channelRepo,
      IntegrationProperties.SlackConfig config,
      String hmacSecret) {
    this.slackClient = slackClient;
    this.integrationRepo = integrationRepo;
    this.channelRepo = channelRepo;
    this.config = config;
    this.hmacSecret = hmacSecret;
  }

  /** Generates the Slack OAuth V2 install URL with a signed state parameter. */
  public String generateInstallUrl(String orgId) {
    String state = buildState(orgId);
    return "https://slack.com/oauth/v2/authorize"
        + "?client_id="
        + enc(config.getClientId())
        + "&scope="
        + enc(config.getBotScopes())
        + "&redirect_uri="
        + enc(config.getRedirectUri())
        + "&state="
        + enc(state);
  }

  /**
   * Handles the OAuth callback — validates state, exchanges code, persists tokens.
   *
   * @return the persisted integration entity
   * @throws IllegalArgumentException if state is invalid or expired
   */
  @Transactional
  @SuppressWarnings("unchecked")
  public OrgSlackIntegration handleCallback(String code, String state, String installedByUserId) {
    String orgId = validateState(state);

    Map<String, Object> resp =
        slackClient.oauthV2Access(
            config.getClientId(), config.getClientSecret(), code, config.getRedirectUri());

    if (!Boolean.TRUE.equals(resp.get("ok"))) {
      throw new IllegalStateException("Slack OAuth failed: " + resp.get("error"));
    }

    // Slack V2 response: access_token is a top-level string (bot token)
    String botToken = resp.get("access_token") instanceof String s ? s : "";

    @SuppressWarnings("unchecked")
    Map<String, Object> team =
        resp.get("team") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    String teamId = team != null ? String.valueOf(team.get("id")) : "";
    String teamName = team != null ? String.valueOf(team.get("name")) : "";

    String botUserId = resp.get("bot_user_id") instanceof String s ? s : null;

    // Upsert: if org already has this workspace connected, update the token
    OrgSlackIntegration integration = integrationRepo.findByOrgId(orgId).orElse(null);
    if (integration != null) {
      integration.setBotToken(botToken);
      integration.setTeamId(teamId);
      integration.setTeamName(teamName);
      integration.setBotUserId(botUserId);
      integration.setInstalledBy(installedByUserId);
      integration.setUpdatedAt(Instant.now());
    } else {
      integration =
          OrgSlackIntegration.builder()
              .orgId(orgId)
              .teamId(teamId)
              .teamName(teamName)
              .botToken(botToken)
              .botUserId(botUserId)
              .installedBy(installedByUserId)
              .build();
    }
    return integrationRepo.save(integration);
  }

  /** Disconnects Slack — revokes the bot token and removes all channel mappings. */
  @Transactional
  public void disconnect(String orgId) {
    integrationRepo
        .findByOrgId(orgId)
        .ifPresent(
            integration -> {
              slackClient.authRevoke(integration.getBotToken());
              channelRepo.deleteByOrgId(orgId);
              integrationRepo.delete(integration);
              log.info(
                  "[Slack] Disconnected workspace {} for org {}", integration.getTeamName(), orgId);
            });
  }

  public boolean isConnected(String orgId) {
    return integrationRepo.findByOrgId(orgId).isPresent();
  }

  // ── State parameter (HMAC-signed) ────────────────────────────────────────

  private String buildState(String orgId) {
    String data = orgId + "|" + Instant.now().toEpochMilli();
    String sig = hmac(data);
    return data + "|" + sig;
  }

  private String validateState(String state) {
    if (state == null || state.isBlank())
      throw new IllegalArgumentException("Missing OAuth state parameter");

    String[] parts = state.split("\\|", 3);
    if (parts.length != 3) throw new IllegalArgumentException("Malformed OAuth state");

    String orgId = parts[0];
    long timestamp;
    try {
      timestamp = Long.parseLong(parts[1]);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid state timestamp");
    }

    // Check expiry
    if (System.currentTimeMillis() - timestamp > STATE_TTL_MS)
      throw new IllegalArgumentException("OAuth state expired");

    // Verify HMAC
    String expectedSig = hmac(orgId + "|" + parts[1]);
    if (!expectedSig.equals(parts[2]))
      throw new IllegalArgumentException("Invalid OAuth state signature");

    return orgId;
  }

  private String hmac(String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(rawHmac);
    } catch (Exception e) {
      throw new RuntimeException("HMAC computation failed", e);
    }
  }

  private static String enc(String value) {
    return URLEncoder.encode(value != null ? value : "", StandardCharsets.UTF_8);
  }
}
