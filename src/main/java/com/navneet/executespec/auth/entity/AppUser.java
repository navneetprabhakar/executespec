package com.navneet.executespec.auth.entity;

import java.time.Instant;

import jakarta.persistence.*;

import com.navneet.executespec.crypto.EncryptedValueConverter;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "app_user")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppUser {

  @Id private String id;

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

  /**
   * R-161 (V152) — v3 Free-tier lifetime run credit balance. Decremented by LifetimeCreditService
   * on each Free-tier run; incremented by 5 on referral fulfilment (R-162). v2 surfaces ignore this
   * column entirely; v2 enforcement still uses personalRunCount + monthly resets. Greenfield doc
   * §7.5 / D-002.
   */
  @Column(name = "lifetime_run_credit_balance", nullable = false)
  @Builder.Default
  private int lifetimeRunCreditBalance = 5;

  @Column(name = "lifetime_run_credit_cap", nullable = false)
  @Builder.Default
  private int lifetimeRunCreditCap = 25;

  /**
   * R-161 — referrer's user id when this user signed up via a referral link. Drives the
   * +5-per-invite credit grant on the referee's first successful run (D-003 default).
   */
  @Column(name = "referred_by_user_id", length = 64)
  private String referredByUserId;

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

  /**
   * Personal GitHub PAT — used to create/push repos after merge. Encrypted at rest via {@link
   * EncryptedValueConverter} (C7 — audit 2026-05-29; previously the only credential field stored
   * cleartext). Legacy plaintext rows decrypt as-is and are re-encrypted on the next write.
   */
  @Convert(converter = EncryptedValueConverter.class)
  @Column(name = "github_pat")
  private String githubPat;

  /** GitHub username or org name to create repos under (e.g. "navneet" or "mycompany"). */
  @Column(name = "github_owner")
  private String githubOwner;

  /** Bitbucket username for API authentication (Basic auth). */
  @Column(name = "bitbucket_username")
  private String bitbucketUsername;

  /** Bitbucket app password — encrypted at rest via AES-256-GCM. */
  @Convert(converter = EncryptedValueConverter.class)
  @Column(name = "bitbucket_app_password")
  private String bitbucketAppPassword;

  /** Bitbucket workspace slug (e.g. "myteam"). */
  @Column(name = "bitbucket_workspace")
  private String bitbucketWorkspace;

  /** Bitbucket repository slug (e.g. "my-repo"). */
  @Column(name = "bitbucket_repo_slug")
  private String bitbucketRepoSlug;

  @Convert(converter = EncryptedValueConverter.class)
  @Column(name = "openai_api_key")
  private String openAiApiKey;

  @Convert(converter = EncryptedValueConverter.class)
  @Column(name = "anthropic_api_key")
  private String anthropicApiKey;

  @Convert(converter = EncryptedValueConverter.class)
  @Column(name = "gemini_api_key")
  private String geminiApiKey;

  @Column(name = "jira_base_url")
  private String jiraBaseUrl;

  @Column(name = "jira_email")
  private String jiraEmail;

  @Convert(converter = EncryptedValueConverter.class)
  @Column(name = "jira_api_token")
  private String jiraApiToken;

  @Column(name = "stripe_customer_id")
  private String stripeCustomerId;

  /** Preferred payment gateway: "razorpay" or "stripe". */
  @Column(name = "payment_gateway", length = 20)
  @Builder.Default
  private String paymentGateway = "razorpay";

  @Column(name = "confluence_base_url")
  private String confluenceBaseUrl;

  @Column(name = "confluence_email")
  private String confluenceEmail;

  @Convert(converter = EncryptedValueConverter.class)
  @Column(name = "confluence_api_token")
  private String confluenceApiToken;

  /**
   * Per-user JWT invalidation timestamp. If set, any JWT with {@code iat < tokenInvalidatedAt} is
   * rejected. Null means no per-user invalidation (global epoch check still applies).
   */
  @Column(name = "token_invalidated_at")
  private Instant tokenInvalidatedAt;

  @Column(name = "oauth_provider")
  private String oauthProvider;

  @Column(name = "oauth_id")
  private String oauthId;

  @Column(name = "avatar_url")
  private String avatarUrl;

  @Column(name = "view_mode")
  @Builder.Default
  private String viewMode = "vibe";

  // Wave 2.1 — signup attribution (V125 migration)
  // Captured from signup request (utm_source / utm_campaign / ref). Null for
  // pre-existing users + direct-navigation signups. Used as a cohort
  // dimension on every activation event forwarded to PostHog.

  @Column(name = "signup_source", length = 64)
  private String signupSource;

  @Column(name = "signup_utm_source", length = 64)
  private String signupUtmSource;

  @Column(name = "signup_utm_campaign", length = 128)
  private String signupUtmCampaign;

  /**
   * Wave 2.2 — id of the disposable sandbox project auto-provisioned on first login. Null for
   * pre-existing users and users who skip /welcome onboarding. Nullable FK with ON DELETE SET NULL
   * — deleting the sandbox project doesn't cascade-delete the user.
   */
  @Column(name = "sandbox_project_id", length = 255)
  private String sandboxProjectId;
}
