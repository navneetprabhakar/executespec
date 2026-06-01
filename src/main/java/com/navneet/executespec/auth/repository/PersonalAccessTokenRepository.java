package com.navneet.executespec.auth.repository;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.auth.entity.PersonalAccessToken;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonalAccessTokenRepository extends JpaRepository<PersonalAccessToken, String> {

  /**
   * Hot-path lookup for authentication. Excludes revoked tokens; expiry is checked in the service.
   */
  Optional<PersonalAccessToken> findByTokenHashAndRevokedAtIsNull(String tokenHash);

  /** Management list — a user's tokens, newest first. Includes revoked rows for the audit trail. */
  List<PersonalAccessToken> findByUserIdOrderByCreatedAtDesc(String userId);

  Optional<PersonalAccessToken> findByIdAndUserId(String id, String userId);

  /** Active (non-revoked) tokens for a user — used to enforce the per-tier device-login cap. */
  List<PersonalAccessToken> findByUserIdAndRevokedAtIsNull(String userId);
}
