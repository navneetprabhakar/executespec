package com.navneet.executespec.project.template;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * v2.5.2 — reusable requirement skeleton. Scoped per-project or per-org;
 * picked by the user inside ExecutionSetup. Slot interpolation
 * ({@code {{ENTITY}}}, {@code {{ENDPOINT}}}, ...) is purely client-side.
 */
@Entity
@Table(name = "project_spec_template")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectSpecTemplate {

    public enum OwnerType { PROJECT, ORG }

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 10)
    private OwnerType ownerType;

    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "requirement_template", nullable = false, columnDefinition = "TEXT")
    private String requirementTemplate;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }
}
