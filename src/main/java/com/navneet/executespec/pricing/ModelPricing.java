package com.navneet.executespec.pricing;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "model_pricing")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelPricing {

    @EmbeddedId
    private ModelPricingId id;

    @Column(nullable = false)
    private Double inputCostPer1m;

    @Column(nullable = false)
    private Double outputCostPer1m;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(nullable = false)
    private Instant updatedAt;
}
