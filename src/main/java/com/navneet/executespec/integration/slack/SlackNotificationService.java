package com.navneet.executespec.integration.slack;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.integration.slack.model.*;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Core Slack notification service. Listens for {@link RunEvent}s published by the {@link
 * com.navneet.executespec.sse.SseBroadcaster} and dispatches Block Kit messages to configured Slack
 * channels.
 *
 * <p>All processing is async — pipeline execution is never blocked.
 */
@Service
@Slf4j
@ConditionalOnProperty("agent.integrations.slack.enabled")
public class SlackNotificationService {

  private final OrgSlackIntegrationRepository integrationRepo;
  private final ProjectSlackChannelRepository channelRepo;
  private final SlackNotificationLogRepository logRepo;
  private final OrgRepository orgRepo;
  private final SlackClient slackClient;
  private final SlackMessageBuilder messageBuilder;
  private final SlackRateLimiter rateLimiter;
  private final StateStore stateStore;
  private final TaskExecutor slackExecutor;

  public SlackNotificationService(
      OrgSlackIntegrationRepository integrationRepo,
      ProjectSlackChannelRepository channelRepo,
      SlackNotificationLogRepository logRepo,
      OrgRepository orgRepo,
      SlackClient slackClient,
      SlackMessageBuilder messageBuilder,
      SlackRateLimiter rateLimiter,
      StateStore stateStore,
      @Qualifier("slackNotificationExecutor") TaskExecutor slackExecutor) {
    this.integrationRepo = integrationRepo;
    this.channelRepo = channelRepo;
    this.logRepo = logRepo;
    this.orgRepo = orgRepo;
    this.slackClient = slackClient;
    this.messageBuilder = messageBuilder;
    this.rateLimiter = rateLimiter;
    this.stateStore = stateStore;
    this.slackExecutor = slackExecutor;
  }

  /**
   * Spring event listener — receives every {@link RunEvent} published by SseBroadcaster. Dispatches
   * processing to the dedicated Slack thread pool.
   */
  @EventListener
  public void onRunEvent(RunEvent event) {
    if (event == null || event.runId() == null) return;
    slackExecutor.execute(
        () -> {
          try {
            processEvent(event);
          } catch (Exception e) {
            log.error(
                "[Slack] Failed to process event {} for run {}: {}",
                event.type(),
                event.runId(),
                e.getMessage());
          }
        });
  }

  /** Sends a test message to a specific channel for the given org. */
  public void sendTestMessage(String orgId, String channelId) {
    OrgSlackIntegration integration =
        integrationRepo
            .findByOrgId(orgId)
            .orElseThrow(() -> new IllegalStateException("Slack not connected"));

    List<Map<String, Object>> blocks =
        List.of(
            Map.of(
                "type",
                "header",
                "text",
                Map.of(
                    "type", "plain_text", "text", "\uD83D\uDD14 Test Notification", "emoji", true)),
            Map.of(
                "type",
                "section",
                "text",
                Map.of(
                    "type",
                    "mrkdwn",
                    "text",
                    "This is a test notification from ExecuteSpec. If you see this, Slack"
                        + " notifications are configured correctly.")),
            Map.of(
                "type",
                "context",
                "elements",
                List.of(Map.of("type", "mrkdwn", "text", "ExecuteSpec Slack Integration"))));

    Map<String, Object> resp =
        slackClient.chatPostMessage(integration.getBotToken(), channelId, blocks, null);

    String status = Boolean.TRUE.equals(resp.get("ok")) ? "SENT" : "FAILED";
    saveLog(
        orgId,
        null,
        channelId,
        "TEST",
        status,
        status.equals("FAILED") ? String.valueOf(resp.get("error")) : null,
        (String) resp.get("ts"));
  }

  private void processEvent(RunEvent event) {
    // 1. Resolve Slack event type from RunEvent
    SlackEventType slackEvent = SlackEventType.resolve(event.type(), event.payload());
    if (slackEvent == null) return;

    // 2. Load run to get orgId + projectId
    PipelineRun run = stateStore.findRun(event.runId()).orElse(null);
    if (run == null || run.getOrgId() == null) return;

    // 3. Check org has Slack connected
    OrgSlackIntegration integration = integrationRepo.findByOrgId(run.getOrgId()).orElse(null);
    if (integration == null) return;

    // 4. Tier check — PRO and ENTERPRISE only
    Org org = orgRepo.findById(run.getOrgId()).orElse(null);
    if (org == null || "FREE".equalsIgnoreCase(org.getTier())) return;

    // 5. Find channel mappings for this run's project
    if (run.getProjectId() == null) return; // Slack only notifies project-linked runs
    List<ProjectSlackChannel> channels = channelRepo.findByProjectId(run.getProjectId());
    if (channels.isEmpty()) return;

    // 6. Build message payload once (reuse across channels)
    Map<String, Object> payload;
    try {
      payload = messageBuilder.build(slackEvent, run, event.payload());
    } catch (Exception e) {
      log.error("[Slack] Failed to build message for event {}: {}", slackEvent, e.getMessage());
      return;
    }

    // 7. Send to each channel (with verbosity filter + rate limiting)
    for (ProjectSlackChannel channel : channels) {
      try {
        SlackVerbosity verbosity = SlackVerbosity.valueOf(channel.getVerbosity());
        if (!verbosity.includes(slackEvent)) continue;

        rateLimiter.acquire(integration.getTeamId());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) payload.get("blocks");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attachments =
            (List<Map<String, Object>>) payload.get("attachments");

        Map<String, Object> resp =
            slackClient.chatPostMessage(
                integration.getBotToken(), channel.getChannelId(), blocks, attachments);

        if (Boolean.TRUE.equals(resp.get("ok"))) {
          saveLog(
              run.getOrgId(),
              run.getRunId(),
              channel.getChannelId(),
              slackEvent.name(),
              "SENT",
              null,
              (String) resp.get("ts"));
        } else {
          String error = String.valueOf(resp.get("error"));
          saveLog(
              run.getOrgId(),
              run.getRunId(),
              channel.getChannelId(),
              slackEvent.name(),
              "FAILED",
              error,
              null);
          handleSlackError(error, integration, run.getOrgId());
        }
      } catch (Exception e) {
        saveLog(
            run.getOrgId(),
            run.getRunId(),
            channel.getChannelId(),
            slackEvent.name(),
            "FAILED",
            e.getMessage(),
            null);
      }
    }
  }

  /** Handle specific Slack errors (e.g., revoked token, deleted channel). */
  private void handleSlackError(String error, OrgSlackIntegration integration, String orgId) {
    if ("token_revoked".equals(error) || "invalid_auth".equals(error)) {
      log.warn("[Slack] Bot token revoked/invalid for org {} — removing integration", orgId);
      channelRepo.deleteByOrgId(orgId);
      integrationRepo.delete(integration);
    } else if ("channel_not_found".equals(error) || "not_in_channel".equals(error)) {
      log.warn("[Slack] Channel issue for org {}: {}", orgId, error);
    }
  }

  private void saveLog(
      String orgId,
      String runId,
      String channelId,
      String eventType,
      String status,
      String error,
      String slackTs) {
    try {
      logRepo.save(
          SlackNotificationLog.builder()
              .orgId(orgId)
              .runId(runId)
              .channelId(channelId)
              .eventType(eventType)
              .status(status)
              .errorMessage(error)
              .slackTs(slackTs)
              .createdAt(Instant.now())
              .build());
    } catch (Exception e) {
      log.warn("[Slack] Failed to save notification log: {}", e.getMessage());
    }
  }
}
