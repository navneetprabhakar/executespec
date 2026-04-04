package com.navneet.executespec.auth.repository;

import com.navneet.executespec.auth.entity.AppUser;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

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
}
