package com.navneet.executespec.billing.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "seller_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SellerConfig {

    @Id
    @Column(length = 20)
    @Builder.Default
    private String id = "DEFAULT";

    @Column(name = "legal_name", nullable = false)
    private String legalName;

    @Column(nullable = false, length = 15)
    private String gstin;

    @Column(length = 10)
    private String pan;

    @Column(name = "address_line1")
    private String addressLine1;

    @Column(name = "address_line2")
    private String addressLine2;

    @Column(length = 100)
    private String city;

    @Column(name = "state_code", nullable = false, length = 2)
    private String stateCode;

    @Column(name = "state_name", length = 100)
    private String stateName;

    @Column(name = "postal_code", length = 10)
    private String postalCode;

    @Column(length = 2, nullable = false)
    @Builder.Default
    private String country = "IN";

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
