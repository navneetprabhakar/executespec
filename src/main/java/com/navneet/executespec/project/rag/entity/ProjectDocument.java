package com.navneet.executespec.project.rag.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "project_document")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProjectDocument {

    public enum Status { UPLOADED, EXTRACTING, EMBEDDING, READY, FAILED }

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "path_relative", length = 500, nullable = false)
    private String pathRelative;

    @Column(length = 32, nullable = false)
    private String source;

    @Column(length = 255)
    private String filename;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(length = 64)
    private String sha256;

    @Column(name = "uploaded_by", length = 64)
    private String uploadedBy;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private Status status;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;
}
