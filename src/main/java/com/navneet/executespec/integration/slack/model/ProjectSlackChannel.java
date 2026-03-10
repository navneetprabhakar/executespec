package com.navneet.executespec.integration.slack.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "project_slack_channel")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectSlackChannel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private String projectId;

    @Column(name = "org_id", nullable = false)
    private String orgId;

    @Column(name = "channel_id", nullable = false)
    private String channelId;

    @Column(name = "channel_name")
    private String channelName;

    @Column(nullable = false)
    @Builder.Default
    private String verbosity = "STANDARD";

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
