package com.navneet.executespec.auth.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import com.navneet.executespec.auth.entity.AppUser;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, String> {

  /** Pessimistic lock for atomic quota enforcement (prevents TOCTOU race on run count). */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT u FROM AppUser u WHERE u.id = :id")
  Optional<AppUser> findByIdForUpdate(@Param("id") String id);

  Optional<AppUser> findByEmail(String email);

  Optional<AppUser> findByOauthProviderAndOauthId(String oauthProvider, String oauthId);

  boolean existsByEmail(String email);

  List<AppUser> findByOrgId(String orgId);

  @Query("SELECT u FROM AppUser u WHERE u.status != 'DELETED' ORDER BY u.createdAt DESC")
  List<AppUser> findAllActive();

  @Query("SELECT u FROM AppUser u WHERE u.orgId = :orgId AND u.status = 'ACTIVE'")
  List<AppUser> findActiveByOrgId(@Param("orgId") String orgId);

  /** Solo users: active, no org (FREE tier individuals). */
  @Query("SELECT u FROM AppUser u WHERE u.status = 'ACTIVE' AND u.orgId IS NULL")
  List<AppUser> findActiveSoloUsers();

  /**
   * B-08 fix (2026-05-09) — bulk reset of personal_run_count to 0 for the supplied user IDs.
   * Replaces the per-user save() loop in {@link
   * com.navneet.executespec.auth.service.MonthlyRunCounterResetJob} that opened a transaction
   * per row and triggered N+1 flushes at midnight IST. With 10K+ users this took minutes and
   * blocked concurrent quota checks reading mid-job state.
   *
   * <p>Driver passes the set of user IDs the per-user predicate already approved for reset (so
   * billing-cycle anchors stay honoured). Returns the number of rows touched, matching the
   * Spring Data convention.
   */
  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.transaction.annotation.Transactional
  @Query(
      "UPDATE AppUser u SET u.personalRunCount = 0, u.runCountResetAt = :now WHERE u.id IN :ids")
  int bulkResetPersonalRunCount(
      @Param("ids") List<String> ids, @Param("now") java.time.Instant now);
}
