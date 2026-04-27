package com.navneet.executespec.auth.entity;

import com.navneet.executespec.crypto.EncryptedValueConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    /**
     * v2.6.0 — seat count, populated from BillingSubscription.totalSeats on
     * webhook events. Drives the TEAM tier shared credit pool (seats × 50).
     * Default 1 so non-TEAM orgs behave identically to pre-v2.6.0.
     */
    @Column(name = "seat_count", nullable = false)
    @Builder.Default
    private int seatCount = 1;

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

    @Convert(converter = EncryptedValueConverter.class)
    @Column(name = "anthropic_api_key")
    private String anthropicApiKey;

    @Convert(converter = EncryptedValueConverter.class)
    @Column(name = "openai_api_key")
    private String openAiApiKey;

    @Convert(converter = EncryptedValueConverter.class)
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

    // ── Payment gateway ──────────────────────────────────────────────────────

    @Column(name = "stripe_customer_id")
    private String stripeCustomerId;

    /** Preferred payment gateway: "razorpay" or "stripe". */
    @Column(name = "payment_gateway", length = 20)
    @Builder.Default
    private String paymentGateway = "razorpay";

    // ── Org-level JIRA credentials ───────────────────────────────────────────
    // Managed by ORG_ADMIN via the Integrations page.
    // Raw tokens are NEVER returned to the client — only boolean flags.

    @Column(name = "jira_base_url")
    private String jiraBaseUrl;

    @Column(name = "jira_email")
    private String jiraEmail;

    @Convert(converter = EncryptedValueConverter.class)
    @Column(name = "jira_api_token")
    private String jiraApiToken;

    /** Default JIRA project key (e.g. "ENG") used by Execution View when a run
     *  has {@code jira_enabled}. Null = skip JIRA sync even when creds exist. */
    @Column(name = "jira_project_key")
    private String jiraProjectKey;

    // ── Org-level Confluence credentials ─────────────────────────────────────

    @Column(name = "confluence_base_url")
    private String confluenceBaseUrl;

    @Column(name = "confluence_email")
    private String confluenceEmail;

    @Convert(converter = EncryptedValueConverter.class)
    @Column(name = "confluence_api_token")
    private String confluenceApiToken;

    // ── V143 (Plan 1 #1) — product surface discriminator ────────────────────
    /** "vibe" (consumer) | "executespec" (B2B). Defaulted to 'executespec' on legacy rows. */
    @Column(name = "product_id", nullable = false)
    @Builder.Default
    private String productId = "executespec";

    // ── V127 (Wave 3) — SSO/SAML on the org ─────────────────────────────────
    /** null | "GOOGLE_OIDC" | "OKTA_SAML" | "AZURE_AD_SAML" */
    @Column(name = "sso_provider", length = 32)
    private String ssoProvider;

    /** When true, password login is rejected — SSO required for org members. */
    @Column(name = "sso_enforced", nullable = false)
    @Builder.Default
    private boolean ssoEnforced = false;

    @Column(name = "sso_metadata_url", length = 1024)
    private String ssoMetadataUrl;

    /** Email-domain → SSO mapping ("acme.com" → use this org's SSO). */
    @Column(name = "sso_domain")
    private String ssoDomain;

    // ── V127 (Wave 3) — GitHub App installation ─────────────────────────────
    /** Numeric installation id from GitHub App install webhook. */
    @Column(name = "github_installation_id")
    private Long githubInstallationId;

    @Column(name = "github_installed_at")
    private Instant githubInstalledAt;

    // ── V148 (Wave 3) — governance caps (M-032 + M-039) ─────────────────────

    /** M-032 — USD threshold above which a run plan can only be approved
     *  by ORG_ADMIN/BILLING_ADMIN/SUPER_ADMIN. NULL = no elevated approval
     *  required (any submitter can approve their own plan). Compared
     *  against the buffered cost estimate at approval time. */
    @Column(name = "require_admin_approval_above_usd", precision = 10, scale = 2)
    private java.math.BigDecimal requireAdminApprovalAboveUsd;

    /** M-039 — comma-separated GitHub-owner allowlist. Empty/NULL = open;
     *  populated = ProjectRepoService rejects link/create attempts whose
     *  owner is not in the list. Use for vendor/contractor scopes that
     *  must stay inside a vetted GH org family. */
    @Column(name = "allowed_github_owners", length = 1024)
    private String allowedGithubOwners;

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
