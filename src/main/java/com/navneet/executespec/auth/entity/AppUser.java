package com.navneet.executespec.auth.entity;

import com.navneet.executespec.crypto.EncryptedValueConverter;
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

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "password_hash")
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

    /** Personal GitHub PAT — used to create/push repos after merge. Stored as plaintext. */
    @Column(name = "github_pat")
    private String githubPat;

    /** GitHub username or org name to create repos under (e.g. "navneet" or "mycompany"). */
    @Column(name = "github_owner")
    private String githubOwner;

    // ── AI provider keys ──────────────────────────────────────────────────────

    @Convert(converter = EncryptedValueConverter.class)
    @Column(name = "openai_api_key")
    private String openAiApiKey;

    @Convert(converter = EncryptedValueConverter.class)
    @Column(name = "anthropic_api_key")
    private String anthropicApiKey;

    @Convert(converter = EncryptedValueConverter.class)
    @Column(name = "gemini_api_key")
    private String geminiApiKey;

    // ── JIRA integration ──────────────────────────────────────────────────────

    @Column(name = "jira_base_url")
    private String jiraBaseUrl;

    @Column(name = "jira_email")
    private String jiraEmail;

    @Convert(converter = EncryptedValueConverter.class)
    @Column(name = "jira_api_token")
    private String jiraApiToken;

    // ── Payment gateway ──────────────────────────────────────────────────────

    @Column(name = "stripe_customer_id")
    private String stripeCustomerId;

    /** Preferred payment gateway: "razorpay" or "stripe". */
    @Column(name = "payment_gateway", length = 20)
    @Builder.Default
    private String paymentGateway = "razorpay";

    // ── Confluence integration ────────────────────────────────────────────────

    @Column(name = "confluence_base_url")
    private String confluenceBaseUrl;

    @Column(name = "confluence_email")
    private String confluenceEmail;

    @Convert(converter = EncryptedValueConverter.class)
    @Column(name = "confluence_api_token")
    private String confluenceApiToken;

    // ── OAuth ────────────────────────────────────────────────────────────────

    @Column(name = "oauth_provider")
    private String oauthProvider;

    @Column(name = "oauth_id")
    private String oauthId;

    @Column(name = "avatar_url")
    private String avatarUrl;

    // ── View mode ────────────────────────────────────────────────────────────

    @Column(name = "view_mode")
    @Builder.Default
    private String viewMode = "vibe";
}
