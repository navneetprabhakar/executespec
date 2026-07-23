package com.navneet.executespec.project.rag.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** DB-authoritative raw bytes for a validated project document. Never serialized to clients. */
@Entity
@Table(name = "project_document_blob")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectDocumentBlob {

  @Id
  @Column(name = "document_id", length = 36)
  private String documentId;

  @JsonIgnore
  @Column(name = "content_bytes", nullable = false, columnDefinition = "bytea")
  private byte[] contentBytes;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;
}
