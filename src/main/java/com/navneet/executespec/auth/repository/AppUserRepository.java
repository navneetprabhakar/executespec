package com.navneet.executespec.auth.repository;

import com.navneet.executespec.auth.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, String> {

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
