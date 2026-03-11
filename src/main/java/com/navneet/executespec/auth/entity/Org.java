package com.navneet.executespec.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "org")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Org {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    /** Comma-separated email domain patterns, e.g. "acme.com,acmecorp.com" */
    @Column(name = "domain_patterns", nullable = false, columnDefinition = "TEXT")
    private String domainPatterns;

    /** PRO | ENTERPRISE */
    @Column(nullable = false)
    @Builder.Default
    private String tier = "PRO";

    /** ACTIVE | SUSPENDED | DELETED */
    @Column(nullable = false)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "monthly_run_count", nullable = false)
    @Builder.Default
    private int monthlyRunCount = 0;

    @Column(name = "run_count_reset_at")
    private Instant runCountResetAt;

    @Column(name = "is_payment_verified", nullable = false)
    @Builder.Default
    private boolean isPaymentVerified = false;

    @Column(name = "payment_ref_id")
    private String paymentRefId;

    @Column(name = "plan_expires_at")
    private Instant planExpiresAt;

    /** Per-org monthly run limit override. NULL = use tier_config default. */
    @Column(name = "monthly_run_limit_override")
    private Integer monthlyRunLimitOverride;

    /** True when this org has provided their own API keys → ENTERPRISE tier becomes unlimited. */
    @Column(name = "api_keys_provided", nullable = false)
    @Builder.Default
    private boolean apiKeysProvided = false;

    // ── Per-org AI provider keys ──────────────────────────────────────────────
    // Used by all org members who have not set a personal key.
    // Resolution order: user key → org key → system key (application.yml).
    // Raw values are NEVER returned to the client — only boolean flags.

    @Column(name = "anthropic_api_key")
    private String anthropicApiKey;

    @Column(name = "openai_api_key")
    private String openAiApiKey;

    @Column(name = "gemini_api_key")
    private String geminiApiKey;

    // ── Key source mode & provider restrictions ──────────────────────────────
    // Controls how API keys are resolved for runs created by this org's members.
    // SYSTEM = always use platform keys; OWN_KEYS = only user/org keys (no fallback);
    // HYBRID = user/org keys first, fall back to platform (default).

    @Column(name = "key_source_mode", nullable = false)
    @Builder.Default
    private String keySourceMode = "HYBRID";

    /** Comma-separated lowercase provider names, e.g. "anthropic,openai". Null = all allowed. */
    @Column(name = "enabled_providers")
    private String enabledProviders;

    // ── Domain enforcement & member limits ────────────────────────────────────

    /** OPEN = escalate cross-domain invites; ENFORCED = reject immediately.
     *  ENTERPRISE orgs are always treated as ENFORCED regardless of this value. */
    @Column(name = "domain_enforcement_mode", nullable = false)
    @Builder.Default
    private String domainEnforcementMode = "OPEN";

    /** Per-org member cap override. NULL = use tier_config.max_org_members default. */
    @Column(name = "max_members")
    private Integer maxMembers;

    /** When true, consumer email domains (gmail, yahoo, etc.) are blocked for invites.
     *  PRO: admin-toggleable. ENTERPRISE: always true. */
    @Column(name = "block_common_domains", nullable = false)
    @Builder.Default
    private boolean blockCommonDomains = false;

    // ── Admin contact ────────────────────────────────────────────────────────

    @Column(name = "admin_email")
    private String adminEmail;

    // ── Org-level JIRA credentials ───────────────────────────────────────────
    // Managed by ORG_ADMIN via the Integrations page.
    // Raw tokens are NEVER returned to the client — only boolean flags.

    @Column(name = "jira_base_url")
    private String jiraBaseUrl;

    @Column(name = "jira_email")
    private String jiraEmail;

    @Column(name = "jira_api_token")
    private String jiraApiToken;

    // ── Org-level Confluence credentials ─────────────────────────────────────

    @Column(name = "confluence_base_url")
    private String confluenceBaseUrl;

    @Column(name = "confluence_email")
    private String confluenceEmail;

    @Column(name = "confluence_api_token")
    private String confluenceApiToken;

    @Column(name = "created_by_user_id", nullable = false)
    private String createdByUserId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
