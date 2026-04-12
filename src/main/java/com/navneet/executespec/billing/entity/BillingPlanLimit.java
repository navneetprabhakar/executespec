package com.navneet.executespec.billing.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "billing_plan_limit")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingPlanLimit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "billing_plan_id", nullable = false)
    private UUID billingPlanId;

    /** RUNS, PROJECTS, STORAGE_MB */
    @Column(nullable = false, length = 50)
    private String metric;

    @Column(name = "max_value", nullable = false)
    private long maxValue;
}
