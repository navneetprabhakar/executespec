package com.navneet.executespec.auth.dto;

import java.time.Instant;

import com.navneet.executespec.auth.entity.PersonalAccessToken;

/** Safe view of a Personal Access Token for the management list — never carries the secret. */
public record PatDto(
    String id,
    String name,
    String tokenPrefix,
    String scopes,
    Instant createdAt,
    Instant lastUsedAt,
    Instant expiresAt,
    boolean revoked) {

  public static PatDto from(PersonalAccessToken t) {
    return new PatDto(
        t.getId(),
        t.getName(),
        t.getTokenPrefix(),
        t.getScopes(),
        t.getCreatedAt(),
        t.getLastUsedAt(),
        t.getExpiresAt(),
        t.getRevokedAt() != null);
  }
}
