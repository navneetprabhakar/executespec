package com.navneet.executespec.billing.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "billing_usage")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "org_id")
    private String orgId;

    /** RUNS, PROJECTS, STORAGE_MB */
    @Column(nullable = false, length = 50)
    private String metric;

    @Column(name = "current_value", nullable = false)
    private long currentValue;

    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;

    @Column(name = "last_updated", nullable = false)
    private Instant lastUpdated;

    @PrePersist
    void prePersist() {
        if (lastUpdated == null) lastUpdated = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        lastUpdated = Instant.now();
    }
}
