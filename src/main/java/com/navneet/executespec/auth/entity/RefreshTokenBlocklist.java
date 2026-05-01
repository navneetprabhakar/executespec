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

@Entity
@Table(name = "refresh_token_blocklist")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshTokenBlocklist {

  /** JWT ID (jti claim) */
  @Id private String jti;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Column(name = "blocked_at", nullable = false)
  @Builder.Default
  private Instant blockedAt = Instant.now();

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;
}
