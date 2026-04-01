package com.navneet.executespec.config.featureflag;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "feature_flag")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeatureFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "flag_key", nullable = false, unique = true, length = 128)
    private String flagKey;

    @Column(name = "flag_value", nullable = false, length = 512)
    private String flagValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 20)
    private ValueType valueType;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    private Scope scope;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public enum ValueType {
        BOOLEAN, STRING, INTEGER, PERCENTAGE
    }

    public enum Scope {
        PLATFORM, ORG
    }

    @PrePersist
    @PreUpdate
    void setTimestamp() {
        this.updatedAt = Instant.now();
    }
}
