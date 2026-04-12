package com.navneet.executespec.deployment.preview.vm;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "preview_metrics")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PreviewMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "preview_id")
    private String previewId;

    @Column(name = "vm_id")
    private String vmId;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
