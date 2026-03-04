package com.navneet.agentteam.auth.repository;

import com.navneet.agentteam.auth.entity.RefreshTokenBlocklist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface RefreshTokenBlocklistRepository extends JpaRepository<RefreshTokenBlocklist, String> {

    boolean existsByJti(String jti);

    @Modifying
    @Query("DELETE FROM RefreshTokenBlocklist r WHERE r.expiresAt < :now")
    void deleteExpired(@Param("now") Instant now);
}
