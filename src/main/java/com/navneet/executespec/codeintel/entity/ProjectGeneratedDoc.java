package com.navneet.executespec.codeintel.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_generated_doc")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectGeneratedDoc {

    public enum DocType { EXPLANATION, SETUP }

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "project_id", nullable = false, columnDefinition = "uuid")
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 32)
    private DocType docType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(name = "generated_by", length = 36)
    private String generatedBy;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "model_used", length = 64)
    private String modelUsed;

    @Column(name = "input_tokens", nullable = false)
    @Builder.Default
    private Integer inputTokens = 0;

    @Column(name = "output_tokens", nullable = false)
    @Builder.Default
    private Integer outputTokens = 0;

    @PrePersist
    void prePersist() {
        if (generatedAt == null) generatedAt = Instant.now();
    }
}
