package com.navneet.executespec.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "refresh_token_blocklist")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshTokenBlocklist {

    /** JWT ID (jti claim) */
    @Id
    private String jti;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "blocked_at", nullable = false)
    @Builder.Default
    private Instant blockedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
