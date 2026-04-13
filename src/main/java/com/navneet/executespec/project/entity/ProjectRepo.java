package com.navneet.executespec.project.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Dual-scoped repo registry entry. A repo is owned at ORG scope (shared across
 * every project in the org, managed by ORG_ADMIN) or USER scope (private to the
 * linking user). Exactly one of {@link #ownerOrgId} / {@link #ownerUserId} is
 * set — enforced by the CHECK constraint in V81.
 *
 * Linking a repo to a project is a separate operation via {@link ProjectRepoLink}.
 */
@Entity
@Table(name = "project_repo")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectRepo {

    public enum Scope { ORG, USER }

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Scope scope;

    @Column(name = "owner_org_id")
    private String ownerOrgId;

    @Column(name = "owner_user_id")
    private String ownerUserId;

    @Column(name = "github_repo_full_name", nullable = false)
    private String githubRepoFullName;

    @Column(name = "github_owner", nullable = false)
    private String githubOwner;

    @Column(name = "default_branch")
    @Builder.Default
    private String defaultBranch = "main";

    @Column(name = "last_synced_sha", length = 40)
    private String lastSyncedSha;

    /** JSON-serialised {@code StackInfo} ({language, framework, version}) or null. */
    @Column(name = "detected_stack", columnDefinition = "jsonb")
    private String detectedStack;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
