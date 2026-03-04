package com.navneet.agentteam.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Global tier configuration — run quotas editable by super admin at runtime.
 * One row per tier: FREE, PRO, ENTERPRISE.
 */
@Entity
@Table(name = "tier_config")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TierConfig {

    /** Tier identifier — FREE | PRO | ENTERPRISE */
    @Id
    private String tier;

    /** Monthly run limit. -1 means unlimited. */
    @Column(name = "monthly_run_limit", nullable = false)
    private int monthlyRunLimit;

    /**
     * When true, the UI shows this tier's limit as fixed (hint only).
     * Super admin can still edit it.
     */
    @Column(name = "is_fixed", nullable = false)
    private boolean isFixed;

    @Column(name = "description")
    private String description;
}
