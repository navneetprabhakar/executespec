package com.navneet.executespec.auth.entity;

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

    /** Max org members. -1 = unlimited. FREE=-1 (N/A), PRO=10, ENT=-1. */
    @Column(name = "max_org_members", nullable = false)
    private int maxOrgMembers = -1;

    /** Max chat messages per run. 0 = disabled. FREE=0, PRO/ENT=10. */
    @Column(name = "max_chat_messages", nullable = false)
    private int maxChatMessages = 10;

    /** Max concurrent runs. FREE=1, PRO=3, ENT=10. */
    @Column(name = "concurrent_run_limit", nullable = false)
    private int concurrentRunLimit = 1;
}
