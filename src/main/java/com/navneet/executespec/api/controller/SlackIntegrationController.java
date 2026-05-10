package com.navneet.executespec.api.controller;

import java.net.URI;
import java.time.Instant;
import java.util.*;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.integration.slack.SlackClient;
import com.navneet.executespec.integration.slack.SlackNotificationService;
import com.navneet.executespec.integration.slack.SlackOAuthService;
import com.navneet.executespec.integration.slack.model.*;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST endpoints for the Slack integration — OAuth flow, channel mapping, test notifications, and
 * audit logs.
 */
@RestController
@RequestMapping("/api/integrations/slack")
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty("agent.integrations.slack.enabled")
public class SlackIntegrationController {

  private final SlackOAuthService oauthService;
  private final SlackClient slackClient;
  private final SlackNotificationService notificationService;
  private final OrgSlackIntegrationRepository integrationRepo;
  private final ProjectSlackChannelRepository channelRepo;
  private final SlackNotificationLogRepository logRepo;
  private final RunAccessGuard guard;
  // 2026-05-09 (B-tenancy fix from audit) — `requireAuthenticated` alone on
  // /projects/{projectId}/* paths let any authed user enumerate or modify
  // another project's Slack channel mappings. ProjectAccessGuard.assertCanRead
  // gates each project-scoped endpoint on org membership / project_member row.
  // The pre-existing `requireOrgAdmin` stays for the write paths so non-admin
  // members can read but not mutate.
  private final ProjectAccessGuard projectAccessGuard;

  // ── OAuth flow ────────────────────────────────────────────────────────────

  /** Returns the Slack OAuth install URL. The frontend redirects the user here. */
  @GetMapping("/install")
  public ResponseEntity<Map<String, String>> getInstallUrl(
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireOrgAdmin(userDetails);
    String orgId = userDetails.getUser().getOrgId();
    if (orgId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User has no org");
    String url = oauthService.generateInstallUrl(orgId);
    return ResponseEntity.ok(Map.of("installUrl", url));
  }

  /**
   * Slack OAuth V2 callback. Exchanges code for bot token, persists integration. Redirects to
   * frontend with success/error query params.
   *
   * <p>This endpoint is permitAll in SecurityConfig — Slack redirects without a JWT. Security
   * relies on the HMAC-signed state parameter.
   */
  @GetMapping("/oauth/callback")
  public ResponseEntity<Void> oauthCallback(@RequestParam String code, @RequestParam String state) {
    try {
      OrgSlackIntegration integration = oauthService.handleCallback(code, state, null);
      log.info(
          "[Slack] OAuth success for org {}, workspace {}",
          integration.getOrgId(),
          integration.getTeamName());
      return ResponseEntity.status(HttpStatus.FOUND)
          .location(URI.create("/integrations?slack=connected"))
          .build();
    } catch (Exception e) {
      // 2026-05-09 (#6 / B-sec from audit) — DO NOT include the raw exception
      // message in the redirect URL. Two reasons: (a) it's an open
      // information-disclosure surface (stack-trace fragments / DB constraint
      // names / token format hints leak through the user's browser history +
      // referrer headers); (b) the message is attacker-controllable when the
      // OAuth state HMAC fails, so it doubles as a dangling-content / phishing
      // primitive (`?msg=Your+session+expired,+sign+in+again+at+evil.tld`).
      // Server-side log keeps the full detail; the redirect carries only an
      // opaque error code that the frontend maps to user-facing copy.
      String code0 = errorCodeFor(e);
      log.error("[Slack] OAuth callback failed (code={}): {}", code0, e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.FOUND)
          .location(URI.create("/integrations?slack=error&code=" + code0))
          .build();
    }
  }

  /**
   * Maps an OAuth-callback exception to a small, allowlisted error code the frontend can translate
   * to user-facing copy. New cases are added explicitly so a future stack trace can't silently leak
   * through. Defaults to {@code unknown}.
   */
  private String errorCodeFor(Exception e) {
    String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
    if (e instanceof java.lang.SecurityException) return "invalid_state";
    if (msg.contains("state")) return "invalid_state";
    if (msg.contains("expired")) return "expired_state";
    if (msg.contains("token") || msg.contains("oauth_v2")) return "oauth_exchange_failed";
    if (msg.contains("workspace") || msg.contains("team")) return "workspace_lookup_failed";
    return "unknown";
  }

  /** Disconnects Slack — revokes token, removes all channel mappings. */
  @DeleteMapping("/disconnect")
  @Transactional
  public ResponseEntity<Map<String, String>> disconnect(
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireOrgAdmin(userDetails);
    String orgId = userDetails.getUser().getOrgId();
    oauthService.disconnect(orgId);
    return ResponseEntity.ok(Map.of("status", "disconnected"));
  }

  /** Returns the Slack connection status for the current org. */
  @GetMapping("/status")
  public ResponseEntity<Map<String, Object>> getStatus(
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireAuthenticated(userDetails);
    String orgId = userDetails.getUser().getOrgId();
    if (orgId == null) {
      return ResponseEntity.ok(Map.of("connected", false));
    }

    Optional<OrgSlackIntegration> opt = integrationRepo.findByOrgId(orgId);
    if (opt.isEmpty()) {
      return ResponseEntity.ok(Map.of("connected", false));
    }

    OrgSlackIntegration integration = opt.get();
    long channelCount = channelRepo.countByOrgId(orgId);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("connected", true);
    result.put("teamName", integration.getTeamName());
    result.put("teamId", integration.getTeamId());
    result.put("installedBy", integration.getInstalledBy());
    result.put("channelCount", channelCount);
    result.put("installedAt", integration.getInstalledAt().toString());
    return ResponseEntity.ok(result);
  }

  // ── Channel management ────────────────────────────────────────────────────

  /** Lists available Slack channels the bot can post to. */
  @GetMapping("/channels")
  @SuppressWarnings("unchecked")
  public ResponseEntity<List<Map<String, String>>> listSlackChannels(
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireOrgAdmin(userDetails);
    String orgId = userDetails.getUser().getOrgId();
    OrgSlackIntegration integration =
        integrationRepo
            .findByOrgId(orgId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slack not connected"));

    List<Map<String, String>> channels = new ArrayList<>();
    String cursor = null;
    for (int page = 0; page < 10; page++) { // safety limit
      Map<String, Object> resp =
          slackClient.conversationsList(integration.getBotToken(), cursor, 200);
      if (!Boolean.TRUE.equals(resp.get("ok"))) break;
      List<Map<String, Object>> channelList = (List<Map<String, Object>>) resp.get("channels");
      if (channelList != null) {
        for (Map<String, Object> ch : channelList) {
          channels.add(
              Map.of(
                  "channelId", String.valueOf(ch.get("id")),
                  "channelName", String.valueOf(ch.get("name"))));
        }
      }
      Map<String, Object> metadata = (Map<String, Object>) resp.get("response_metadata");
      cursor = metadata != null ? (String) metadata.get("next_cursor") : null;
      if (cursor == null || cursor.isBlank()) break;
    }
    return ResponseEntity.ok(channels);
  }

  /** Gets channel mappings for a specific project. */
  @GetMapping("/projects/{projectId}/channels")
  public ResponseEntity<List<Map<String, Object>>> getProjectChannels(
      @PathVariable String projectId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireAuthenticated(userDetails);
    projectAccessGuard.assertCanRead(projectId, userDetails);
    List<ProjectSlackChannel> mappings = channelRepo.findByProjectId(projectId);
    List<Map<String, Object>> result =
        mappings.stream()
            .map(
                m -> {
                  Map<String, Object> map = new LinkedHashMap<>();
                  map.put("channelId", m.getChannelId());
                  map.put("channelName", m.getChannelName());
                  map.put("verbosity", m.getVerbosity());
                  return map;
                })
            .toList();
    return ResponseEntity.ok(result);
  }

  /** Adds a channel mapping to a project. */
  @PostMapping("/projects/{projectId}/channels")
  public ResponseEntity<Map<String, Object>> addProjectChannel(
      @PathVariable String projectId,
      @RequestBody Map<String, String> body,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireOrgAdmin(userDetails);
    projectAccessGuard.assertCanRead(projectId, userDetails);
    String orgId = userDetails.getUser().getOrgId();
    if (!integrationRepo.findByOrgId(orgId).isPresent()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slack not connected");
    }

    String channelId = body.get("channelId");
    String channelName = body.get("channelName");
    String verbosity = body.getOrDefault("verbosity", "STANDARD");

    // Validate verbosity
    try {
      SlackVerbosity.valueOf(verbosity);
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid verbosity: " + verbosity);
    }

    // Upsert
    ProjectSlackChannel mapping =
        channelRepo
            .findByProjectIdAndChannelId(projectId, channelId)
            .orElse(
                ProjectSlackChannel.builder()
                    .projectId(projectId)
                    .orgId(orgId)
                    .channelId(channelId)
                    .channelName(channelName)
                    .createdBy(userDetails.getUser().getId())
                    .build());
    mapping.setVerbosity(verbosity);
    mapping.setChannelName(channelName);
    mapping.setUpdatedAt(Instant.now());
    channelRepo.save(mapping);

    return ResponseEntity.ok(
        Map.of(
            "channelId", mapping.getChannelId(),
            "channelName", mapping.getChannelName() != null ? mapping.getChannelName() : "",
            "verbosity", mapping.getVerbosity()));
  }

  /** Removes a channel mapping from a project. */
  @DeleteMapping("/projects/{projectId}/channels/{channelId}")
  @Transactional
  public ResponseEntity<Void> removeProjectChannel(
      @PathVariable String projectId,
      @PathVariable String channelId,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireOrgAdmin(userDetails);
    projectAccessGuard.assertCanRead(projectId, userDetails);
    channelRepo.deleteByProjectIdAndChannelId(projectId, channelId);
    return ResponseEntity.noContent().build();
  }

  /** Updates verbosity for a channel mapping. */
  @PutMapping("/projects/{projectId}/channels/{channelId}/verbosity")
  public ResponseEntity<Void> updateVerbosity(
      @PathVariable String projectId,
      @PathVariable String channelId,
      @RequestBody Map<String, String> body,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireOrgAdmin(userDetails);
    projectAccessGuard.assertCanRead(projectId, userDetails);
    String verbosity = body.get("verbosity");
    try {
      SlackVerbosity.valueOf(verbosity);
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid verbosity: " + verbosity);
    }

    ProjectSlackChannel mapping =
        channelRepo
            .findByProjectIdAndChannelId(projectId, channelId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel mapping not found"));
    mapping.setVerbosity(verbosity);
    mapping.setUpdatedAt(Instant.now());
    channelRepo.save(mapping);
    return ResponseEntity.noContent().build();
  }

  // ── Test + audit ──────────────────────────────────────────────────────────

  /** Sends a test message to a Slack channel. */
  @PostMapping("/test")
  public ResponseEntity<Map<String, Object>> sendTestMessage(
      @AuthenticationPrincipal AppUserDetails userDetails, @RequestBody Map<String, String> body) {
    guard.requireOrgAdmin(userDetails);
    String orgId = userDetails.getUser().getOrgId();
    String channelId = body.get("channelId");
    if (channelId == null || channelId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "channelId is required");
    }
    try {
      notificationService.sendTestMessage(orgId, channelId);
      return ResponseEntity.ok(Map.of("ok", true, "message", "Test message sent"));
    } catch (Exception e) {
      return ResponseEntity.ok(Map.of("ok", false, "message", e.getMessage()));
    }
  }

  /** Notification audit log. */
  @GetMapping("/logs")
  public ResponseEntity<List<SlackNotificationLog>> getLogs(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @RequestParam(required = false) String runId) {
    guard.requireOrgAdmin(userDetails);
    if (runId != null && !runId.isBlank()) {
      return ResponseEntity.ok(logRepo.findByRunIdOrderByCreatedAtDesc(runId));
    }
    String orgId = userDetails.getUser().getOrgId();
    return ResponseEntity.ok(
        logRepo.findByOrgIdOrderByCreatedAtDesc(orgId, PageRequest.of(0, 100)));
  }
}
