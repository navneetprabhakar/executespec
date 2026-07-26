package com.navneet.executespec.auth.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.auth.entity.PersonalAccessToken;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

  /**
   * RR-139 — stamp {@code last_used_at} with a narrow conditional update that only touches an
   * ACTIVE row and never writes {@code revoked_at}. The previous whole-entity save could resurrect
   * a concurrently-revoked token: a resolver that read the row before a revocation committed would
   * save back its stale {@code revoked_at=null}, re-enabling a token the owner had just revoked.
   * Gating on {@code revoked_at IS NULL} makes the stamp a no-op (returns 0) once revocation has
   * committed.
   */
  @Modifying
  @Transactional
  @Query(
      "UPDATE PersonalAccessToken t SET t.lastUsedAt = :now WHERE t.id = :id AND t.revokedAt IS"
          + " NULL")
  int stampLastUsedIfActive(@Param("id") String id, @Param("now") Instant now);
}
