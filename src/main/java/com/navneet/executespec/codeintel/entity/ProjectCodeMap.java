package com.navneet.executespec.codeintel.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_code_map")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectCodeMap {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "project_id", nullable = false)
    private String projectId;

    @Column(name = "repo_id", nullable = false)
    private UUID repoId;

    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "generated_from_sha", length = 40)
    private String generatedFromSha;

    @Column(name = "generated_at", nullable = false)
    @Builder.Default
    private Instant generatedAt = Instant.now();
}
