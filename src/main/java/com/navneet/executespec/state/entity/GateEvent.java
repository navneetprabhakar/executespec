package com.navneet.executespec.state.entity;

import com.navneet.executespec.tenant.TenantInterceptor;
import jakarta.persistence.*;
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
