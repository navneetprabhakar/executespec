package com.navneet.executespec.state.entity;

import com.navneet.executespec.recovery.StuckClassification;
import com.navneet.executespec.tenant.TenantInterceptor;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@EntityListeners(TenantInterceptor.class)
@Table(name = "run_recovery",
       indexes = {
           @Index(name = "idx_recovery_original", columnList = "original_run_id"),
           @Index(name = "idx_recovery_restart",  columnList = "restart_run_id")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunRecovery {

    @Id
    private String id;

    @Column(name = "original_run_id", nullable = false)
    private String originalRunId;

    @Column(name = "restart_run_id")
    private String restartRunId;          // null until fresh run created

    @Column(name = "stuck_task_id")
    private String stuckTaskId;           // null if entire run was killed

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StuckClassification classification;

    @Column(name = "classification_detail", columnDefinition = "TEXT")
    private String classificationDetail;

    @Column(name = "action_taken", nullable = false)
    private String actionTaken;           // "TASK_RETRY" | "FRESH_RESTART"

    @Column(name = "original_goal", columnDefinition = "TEXT", nullable = false)
    private String originalGoal;

    @Column(name = "recovered_at", nullable = false)
    private Instant recoveredAt;

    @Column(name = "restart_created_at")
    private Instant restartCreatedAt;

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private String tenantId = "default";

}
