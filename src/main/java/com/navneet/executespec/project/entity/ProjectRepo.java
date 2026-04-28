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

    /** v2.5.2 — whether a PR webhook has been installed on this GitHub repo.
     *  Flipped true by {@code installWebhook} in {@code ProjectRepoService}. */
    @Column(name = "webhook_installed", nullable = false)
    @Builder.Default
    private boolean webhookInstalled = false;

    /** R-322 (D-125 #2) — when the repo is a monorepo and the customer
     *  has dropped a {@code .executespec/root.yml} file declaring
     *  {@code root: <subdir>}, this is the relative path inside the repo
     *  that downstream consumers (codemap, planner, worker) should treat
     *  as the project root. NULL = repo root (today's default). Detected
     *  on attach + sync via the GitHub Contents API. */
    @Column(name = "subdir", length = 512)
    private String subdir;

    /** JSON-serialised {@code StackInfo} ({language, framework, version}) or null. */
    @Column(name = "detected_stack", columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String detectedStack;

    /** v2.6.2 (E1.1) — bootstrap state of the .executespec/ folder in this
     *  repo. Persisted as String for forward-compat with new states without a
     *  schema change; {@link #folderStatusEnum()} normalises NULL → NOT_BOOTSTRAPPED
     *  on read. Set by ExecutespecFolderWriter (E1.4) + BootstrapService (E1.5). */
    @Column(name = "executespec_folder_status", length = 32)
    private String executespecFolderStatus;

    /** v2.6.2 (E1.1) — last-known content SHA of the {@code .executespec/}
     *  folder on the repo's default branch. Used by the drift-detection flow
     *  (E2.8) to skip work when nothing changed since the last sync. */
    @Column(name = "executespec_folder_sha", length = 64)
    private String executespecFolderSha;

    /** v2.6.2 (E1.1) — when {@link #executespecFolderStatus} is
     *  {@code BOOTSTRAP_PR_OPEN}, the URL of the open setup PR so the
     *  dashboard can deep-link the user to it. */
    @Column(name = "executespec_bootstrap_pr_url", columnDefinition = "TEXT")
    private String executespecBootstrapPrUrl;

    /** v2.6.2 (E1.1) — last time we reconciled this row against the repo's
     *  default branch (codemap regen, drift check, manual refresh). NULL
     *  until first reconciliation. */
    @Column(name = "executespec_last_synced_at")
    private Instant executespecLastSyncedAt;

    /** v2.6.2 (E1.4) — write strategy for {@code .executespec/} mutations.
     *  Default (NULL or PR) opens a PR for every change — right for the picky
     *  enterprise persona (every change reviewable, audit trail in git).
     *  DIRECT_PUSH is the opt-in bypass for trusted, high-frequency writes
     *  (codemap regen post-merge) so customers who don't want bot-PR fatigue
     *  can reduce noise; toggled by PROJECT_ADMIN via a future admin endpoint.
     *  See {@link #prModeEnum()} for the NULL → PR normalisation. */
    @Column(name = "executespec_pr_mode", length = 16)
    private String executespecPrMode;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    /**
     * v2.6.2 (E1.1) — strongly-typed read of {@link #executespecFolderStatus}.
     * Treats NULL as {@link FolderStatus#NOT_BOOTSTRAPPED} so existing rows
     * (created before V131) report consistently to the dashboard without a
     * data backfill.
     */
    public FolderStatus folderStatusEnum() {
        if (executespecFolderStatus == null || executespecFolderStatus.isBlank()) {
            return FolderStatus.NOT_BOOTSTRAPPED;
        }
        try {
            return FolderStatus.valueOf(executespecFolderStatus);
        } catch (IllegalArgumentException ex) {
            // Unknown value persisted by a newer node; safest default is
            // NOT_BOOTSTRAPPED so the bootstrap flow can re-evaluate.
            return FolderStatus.NOT_BOOTSTRAPPED;
        }
    }

    /**
     * v2.6.2 (E1.1) — bootstrap lifecycle of the {@code .executespec/} folder
     * for a {@link ProjectRepo}. Stored as String in the DB column for
     * forward-compat (a new node can write a value an older node doesn't
     * recognise without breaking schema).
     */
    public enum FolderStatus {
        /** No record of a {@code .executespec/} bootstrap attempt yet. */
        NOT_BOOTSTRAPPED,
        /** Bootstrap PR opened against the repo; awaiting customer merge. */
        BOOTSTRAP_PR_OPEN,
        /** {@code .executespec/} folder confirmed present on the default branch. */
        BOOTSTRAPPED,
        /** Bootstrap PR could not be opened (PAT scopes, GH errors, etc.). */
        BOOTSTRAP_FAILED
    }

    /**
     * v2.6.2 (E1.4) — strongly-typed read of {@link #executespecPrMode}.
     * Defaults to {@link PrMode#PR} when the column is null, so the safe
     * "every change reviewable" behaviour applies unless a customer
     * explicitly opts into {@link PrMode#DIRECT_PUSH}.
     */
    public PrMode prModeEnum() {
        if (executespecPrMode == null || executespecPrMode.isBlank()) {
            return PrMode.PR;
        }
        try {
            return PrMode.valueOf(executespecPrMode);
        } catch (IllegalArgumentException ex) {
            return PrMode.PR; // unknown forward-compat value → safe default
        }
    }

    /**
     * v2.6.2 (E1.4) — write strategy for {@code .executespec/} mutations.
     * <ul>
     *   <li>{@link #PR} (default) — every change opens a PR. Right for the
     *       picky enterprise persona; auditable; needs human merge.</li>
     *   <li>{@link #DIRECT_PUSH} — opt-in bypass for trusted high-frequency
     *       writes (e.g. codemap regen post-merge) to reduce bot-PR noise.</li>
     * </ul>
     */
    public enum PrMode { PR, DIRECT_PUSH }
}
