package com.navneet.executespec.auth.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Long-lived, revocable credential for programmatic API access (CLI / VS Code extension / CI).
 *
 * <p>Presented as {@code Authorization: Bearer es_pat_…} and resolved by {@code
 * JwtAuthenticationFilter} to the same {@link AppUserDetails} as a JWT for the owning user. Only the
 * SHA-256 hash of the plaintext is stored ({@link #tokenHash}); the credential is shown to the user
 * once at creation and never persisted. See V215 migration.
 */
@Entity
@Table(name = "personal_access_token")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersonalAccessToken {

  @Id private String id;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Column(nullable = false, length = 100)
  private String name;

  /** SHA-256 hex of the full plaintext token. The lookup key; the token itself is never stored. */
  @Column(name = "token_hash", nullable = false, unique = true, length = 64)
  private String tokenHash;

  /** First chars of the plaintext ("es_pat_" + a few random chars) — display only, not a secret. */
  @Column(name = "token_prefix", nullable = false, length = 16)
  private String tokenPrefix;

  /** Comma-separated scopes. v1 issues "full" (acts as the user). */
  @Column(nullable = false, length = 255)
  @Builder.Default
  private String scopes = "full";

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();

  /** Throttled (≤ once / 5 min) on use — an audit hint, not an exact last-call clock. */
  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  /** Null means the token never expires. */
  @Column(name = "expires_at")
  private Instant expiresAt;

  /** Set on revoke. A revoked row is retained for audit but never authenticates. */
  @Column(name = "revoked_at")
  private Instant revokedAt;
}
