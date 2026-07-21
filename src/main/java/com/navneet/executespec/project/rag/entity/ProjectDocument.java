package com.navneet.executespec.project.rag.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.*;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.*;

@Entity
@Table(name = "project_document")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectDocument {

  public enum Status {
    UPLOADED,
    EXTRACTING,
    EMBEDDING,
    INDEXING,
    RETRY_PENDING,
    READY,
    LEXICAL_READY,
    FAILED,
    CANCELLED
  }

  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "project_id", length = 36, nullable = false)
  private String projectId;

  @Column(name = "path_relative", length = 500, nullable = false)
  @JsonIgnore
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
  @JsonIgnore
  private String uploadedBy;

  @Enumerated(EnumType.STRING)
  @Column(length = 20, nullable = false)
  private Status status;

  /** PENDING | SEMANTIC | LEXICAL. V290 keeps retrieval readiness distinct from job state. */
  @Column(name = "retrieval_mode", length = 16, nullable = false)
  @Builder.Default
  private String retrievalMode = "PENDING";

  @Column(name = "embedding_provider", length = 32)
  private String embeddingProvider;

  @Column(name = "embedding_model", length = 128)
  private String embeddingModel;

  @Column(name = "failure_reason", columnDefinition = "TEXT")
  @JsonIgnore
  private String failureReason;

  @Column(name = "error_code", length = 64)
  private String errorCode;

  @Column(name = "page_count")
  private Integer pageCount;

  @Column(name = "extracted_chars")
  private Integer extractedChars;

  @Column(name = "estimated_tokens")
  private Integer estimatedTokens;

  @Column(name = "processing_attempt", nullable = false)
  @Builder.Default
  private Integer processingAttempt = 0;

  @Column(name = "processing_started_at")
  private OffsetDateTime processingStartedAt;

  @Column(name = "processing_completed_at")
  private OffsetDateTime processingCompletedAt;

  @JsonIgnore
  @Column(name = "processing_lease_owner", length = 64)
  private String processingLeaseOwner;

  @JsonIgnore
  @Column(name = "processing_lease_token", nullable = false)
  @Builder.Default
  private Long processingLeaseToken = 0L;

  @JsonIgnore
  @Column(name = "processing_lease_expires_at")
  private OffsetDateTime processingLeaseExpiresAt;

  @Column(name = "cancellation_requested", nullable = false)
  @Builder.Default
  private boolean cancellationRequested = false;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @Column(name = "deleted_at")
  private OffsetDateTime deletedAt;
}
