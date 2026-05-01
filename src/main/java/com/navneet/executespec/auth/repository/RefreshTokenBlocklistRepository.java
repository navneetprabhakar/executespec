package com.navneet.executespec.auth.repository;

import java.time.Instant;

import com.navneet.executespec.auth.entity.RefreshTokenBlocklist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenBlocklistRepository
    extends JpaRepository<RefreshTokenBlocklist, String> {

  boolean existsByJti(String jti);

  @Modifying
  @Query(
      value =
          "INSERT INTO refresh_token_blocklist (jti, user_id, blocked_at, expires_at) "
              + "VALUES (:jti, :userId, NOW(), :expiresAt) ON CONFLICT (jti) DO NOTHING",
      nativeQuery = true)
  void insertIfAbsent(
      @Param("jti") String jti,
      @Param("userId") String userId,
      @Param("expiresAt") Instant expiresAt);

  @Modifying
  @Query("DELETE FROM RefreshTokenBlocklist r WHERE r.expiresAt < :now")
  void deleteExpired(@Param("now") Instant now);
}
