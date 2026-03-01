package com.navneet.agentteam.state.entity;

import com.navneet.agentteam.tenant.TenantInterceptor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@EntityListeners(TenantInterceptor.class)
@Table(name = "gate_event")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GateEvent {

    @Id
    private String id;              // UUID

    private String runId;
    private String gateType;        // "PLAN_APPROVAL" | "MERGE_APPROVAL"
    private Instant openedAt;
    private Instant resolvedAt;
    private Boolean approved;

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private String tenantId = "default";

}
