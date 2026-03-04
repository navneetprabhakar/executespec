package com.navneet.agentteam.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "app_user")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppUser {

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /** SUPER_ADMIN | USER */
    @Column(name = "global_role", nullable = false)
    @Builder.Default
    private String globalRole = "USER";

    /** FREE | PRO | ENTERPRISE */
    @Column(nullable = false)
    @Builder.Default
    private String tier = "FREE";

    /** PENDING_VERIFICATION | ACTIVE | SUSPENDED | DELETED */
    @Column(nullable = false)
    @Builder.Default
    private String status = "PENDING_VERIFICATION";

    @Column(name = "must_change_password", nullable = false)
    @Builder.Default
    private boolean mustChangePassword = false;

    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    /** NULL for free-tier users who have no org */
    @Column(name = "org_id")
    private String orgId;

    /** Monthly run counter — used only for FREE users (no org) */
    @Column(name = "personal_run_count", nullable = false)
    @Builder.Default
    private int personalRunCount = 0;

    @Column(name = "run_count_reset_at")
    private Instant runCountResetAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @Column(name = "deleted_at")
    private Instant deletedAt;

    // ── GitHub integration ────────────────────────────────────────────────────

    /** Personal GitHub PAT — used to create/push repos after merge. Stored encrypted at rest (future). */
    @Column(name = "github_pat")
    private String githubPat;

    /** GitHub username or org name to create repos under (e.g. "navneet" or "mycompany"). */
    @Column(name = "github_owner")
    private String githubOwner;
}
