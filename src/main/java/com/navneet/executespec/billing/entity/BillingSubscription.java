package com.navneet.executespec.billing.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "billing_subscription")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "org_id")
    private String orgId;

    @Column(name = "billing_plan_id", nullable = false)
    private UUID billingPlanId;

    /** NONE, RAZORPAY, STRIPE */
    @Column(name = "payment_gateway", nullable = false, length = 20)
    private String paymentGateway;

    @Column(name = "gateway_subscription_id", length = 100)
    private String gatewaySubscriptionId;

    @Column(name = "gateway_customer_id", length = 100)
    private String gatewayCustomerId;

    /** FREE, CREATED, ACTIVE, PAST_DUE, CANCELLED, DOWNGRADED */
    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "current_period_start")
    private Instant currentPeriodStart;

    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    @Column(name = "total_seats", nullable = false)
    private int totalSeats;

    @Column(name = "extra_seats", nullable = false)
    private int extraSeats;

    @Column(name = "cancel_at_period_end", nullable = false)
    private boolean cancelAtPeriodEnd;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "downgraded_at")
    private Instant downgradedAt;

    @Column(name = "terms_accepted_at")
    private Instant termsAcceptedAt;

    @Column(name = "terms_version", length = 20)
    private String termsVersion;

    @Column(name = "is_founder_pricing", nullable = false)
    private boolean founderPricing;

    @Column(name = "founder_batch")
    private Integer founderBatch;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        var now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
