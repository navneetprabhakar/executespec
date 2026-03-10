package com.navneet.executespec.auth.repository;

import com.navneet.executespec.auth.entity.EmailToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface EmailTokenRepository extends JpaRepository<EmailToken, String> {

    Optional<EmailToken> findByToken(String token);

    Optional<EmailToken> findByTokenAndType(String token, String type);

    @Modifying
    @Query("DELETE FROM EmailToken t WHERE t.expiresAt < :now")
    void deleteExpired(@Param("now") Instant now);
}
