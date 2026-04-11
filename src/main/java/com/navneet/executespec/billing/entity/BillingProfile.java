package com.navneet.executespec.billing.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "billing_profile")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "org_id")
    private String orgId;

    @Column(name = "legal_name", nullable = false)
    private String legalName;

    @Column(name = "billing_email")
    private String billingEmail;

    @Column(length = 20)
    private String phone;

    @Column(length = 15)
    private String gstin;

    @Column(name = "gst_registered", nullable = false)
    private boolean gstRegistered;

    @Column(length = 10)
    private String pan;

    @Column(name = "address_line1")
    private String addressLine1;

    @Column(name = "address_line2")
    private String addressLine2;

    @Column(length = 100)
    private String city;

    @Column(name = "state_code", length = 2)
    private String stateCode;

    @Column(name = "state_name", length = 100)
    private String stateName;

    @Column(name = "postal_code", length = 10)
    private String postalCode;

    @Column(length = 2, nullable = false)
    @Builder.Default
    private String country = "IN";

    @Column(name = "preferred_currency", length = 3, nullable = false)
    @Builder.Default
    private String preferredCurrency = "INR";

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
