package com.navneet.executespec.auth.repository;

import java.time.Instant;
import java.util.Optional;

import com.navneet.executespec.auth.entity.EmailToken;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailTokenRepository extends JpaRepository<EmailToken, String> {

  Optional<EmailToken> findByToken(String token);

  Optional<EmailToken> findByTokenAndType(String token, String type);

  @Modifying
  @Query("DELETE FROM EmailToken t WHERE t.expiresAt < :now")
  void deleteExpired(@Param("now") Instant now);

  /**
   * Atomically claims a single-use token (RR-133, and the single-use half of the RR-127/RR-1336
   * password-reset rebuild). Returns 1 when this caller won the claim and 0 when the token was
   * already used or has expired, so callers must gate every side effect on the return value instead
   * of a read-then-write {@code usedAt} check that two concurrent requests can both pass.
   */
  @Modifying
  @Query(
      "UPDATE EmailToken t SET t.usedAt = :now WHERE t.id = :id AND t.usedAt IS NULL AND"
          + " t.expiresAt > :now")
  int claimIfUnused(@Param("id") String id, @Param("now") Instant now);

  /** Invalidates every still-live token of a type for a user (supersede-on-reissue). */
  @Modifying
  @Query(
      "UPDATE EmailToken t SET t.usedAt = :now WHERE t.userId = :userId AND t.type = :type AND"
          + " t.usedAt IS NULL")
  int invalidateOutstanding(
      @Param("userId") String userId, @Param("type") String type, @Param("now") Instant now);
}
