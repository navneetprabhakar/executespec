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

  interface NameProjection {
    String getFirstName();

    String getLastName();
  }

  @Query("SELECT u.firstName AS firstName, u.lastName AS lastName FROM AppUser u WHERE u.id = :id")
  Optional<NameProjection> findNameById(@Param("id") String id);

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

  /**
   * RR-012 — bounded variant of {@link #findAllActive()} so the admin user listing can page instead
   * of materializing every non-deleted row. Same filter/ordering as the unbounded query.
   */
  @Query("SELECT u FROM AppUser u WHERE u.status != 'DELETED' ORDER BY u.createdAt DESC")
  List<AppUser> findAllActive(org.springframework.data.domain.Pageable pageable);

  @Query("SELECT u FROM AppUser u WHERE u.orgId = :orgId AND u.status = 'ACTIVE'")
  List<AppUser> findActiveByOrgId(@Param("orgId") String orgId);

  /** Solo users: active, no org (FREE tier individuals). */
  @Query("SELECT u FROM AppUser u WHERE u.status = 'ACTIVE' AND u.orgId IS NULL")
  List<AppUser> findActiveSoloUsers();

  /**
   * RR-010 — count of usable platform super-admins, so a status/role mutation can refuse to demote
   * or suspend the last one and lock everyone out of the admin surface.
   */
  @Query(
      "SELECT COUNT(u) FROM AppUser u WHERE u.globalRole = 'SUPER_ADMIN' AND u.status = 'ACTIVE'")
  long countActiveSuperAdmins();
}
