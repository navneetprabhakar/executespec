package com.navneet.executespec.state.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "plan_version")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PlanVersion {
    @Id
    private String id;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(nullable = false)
    private int version;

    @Column(name = "plan_json", columnDefinition = "TEXT", nullable = false)
    private String planJson;

    @Column(nullable = false, length = 64)
    private String source;

    @Column(name = "created_by")
    private String createdBy;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
