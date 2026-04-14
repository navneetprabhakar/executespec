package com.navneet.executespec.auth.entity;

import com.navneet.executespec.router.LlmProvider;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "project")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Project {

    @Id
    private String id;

    @Column(name = "org_id", nullable = false)
    private String orgId;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** ACTIVE | ARCHIVED */
    @Column(nullable = false)
    @Builder.Default
    private String status = "ACTIVE";

    /** User-selected LLM provider for this project. */
    @Column(name = "ai_provider", nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private LlmProvider aiProvider = LlmProvider.ANTHROPIC;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    /** Pilot R5 — branch name template used when pushing run output to GitHub. */
    @Column(name = "branch_naming_pattern")
    @Builder.Default
    private String branchNamingPattern = "executespec/{runId}/{description}";

    /** Pilot R5 — default target branch for PRs created from this project. */
    @Column(name = "target_branch")
    @Builder.Default
    private String targetBranch = "main";

    /** Pilot R5 — JSON array of GitHub usernames to request review from by default. */
    @Column(name = "default_reviewers", columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String defaultReviewers;
}
