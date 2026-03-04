package com.navneet.agentteam.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "org")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Org {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    /** Comma-separated email domain patterns, e.g. "acme.com,acmecorp.com" */
    @Column(name = "domain_patterns", nullable = false, columnDefinition = "TEXT")
    private String domainPatterns;

    /** PRO | ENTERPRISE */
    @Column(nullable = false)
    @Builder.Default
    private String tier = "PRO";

    /** ACTIVE | SUSPENDED | DELETED */
    @Column(nullable = false)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "monthly_run_count", nullable = false)
    @Builder.Default
    private int monthlyRunCount = 0;

    @Column(name = "run_count_reset_at")
    private Instant runCountResetAt;

    @Column(name = "is_payment_verified", nullable = false)
    @Builder.Default
    private boolean isPaymentVerified = false;

    @Column(name = "payment_ref_id")
    private String paymentRefId;

    @Column(name = "plan_expires_at")
    private Instant planExpiresAt;

    /** Per-org monthly run limit override. NULL = use tier_config default. */
    @Column(name = "monthly_run_limit_override")
    private Integer monthlyRunLimitOverride;

    /** True when this org has provided their own API keys → ENTERPRISE tier becomes unlimited. */
    @Column(name = "api_keys_provided", nullable = false)
    @Builder.Default
    private boolean apiKeysProvided = false;

    @Column(name = "created_by_user_id", nullable = false)
    private String createdByUserId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
