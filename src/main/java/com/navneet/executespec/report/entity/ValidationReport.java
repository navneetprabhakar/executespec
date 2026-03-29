package com.navneet.executespec.report.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "validation_report")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationReport {

    @Id
    private String id;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(name = "files_generated", nullable = false)
    @Builder.Default
    private int filesGenerated = 0;

    @Column(name = "languages", nullable = false)
    @Builder.Default
    private String languages = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "pipeline_result", nullable = false, length = 30)
    @Builder.Default
    private PipelineResult pipelineResult = PipelineResult.PASSED;

    @Column(name = "issues_json", columnDefinition = "TEXT", nullable = false)
    @Builder.Default
    private String issuesJson = "[]";

    @Column(name = "checks_passed", nullable = false)
    @Builder.Default
    private String checksPassed = "";

    @Column(name = "report_markdown", columnDefinition = "TEXT")
    private String reportMarkdown;

    @Column(name = "model_used", length = 50)
    private String modelUsed;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "generation_time_ms")
    private Long generationTimeMs;

    @Column(name = "generated_after_improvements", nullable = false)
    @Builder.Default
    private boolean generatedAfterImprovements = false;

    @Column(name = "share_token", length = 64, unique = true)
    private String shareToken;

    @Column(name = "shared_at")
    private Instant sharedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        var now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
