package com.navneet.executespec.integration.slack.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "slack_notification_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlackNotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "org_id", nullable = false)
    private String orgId;

    @Column(name = "run_id")
    private String runId;

    @Column(name = "channel_id", nullable = false)
    private String channelId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    @Builder.Default
    private String status = "SENT";

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "slack_ts")
    private String slackTs;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
