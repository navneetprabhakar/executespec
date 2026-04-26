package com.navneet.executespec.standards;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * E3.1 — where the org's config-as-code (standards / scopes) lives.
 *
 * <p>{@link StorageMode#REPO} = dedicated org-config GitHub repo
 * ({@link #githubFullName} populated; standards live as YAML there);
 * {@link StorageMode#DATABASE} = standards stored as JSONB rows in
 * {@link OrgStandard}. The DATABASE mode is the v1 default per plan §5.7.
 */
@Entity
@Table(name = "org_config_repo")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrgConfigRepo {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "org_id", nullable = false, unique = true, length = 64)
    private String orgId;

    @Column(name = "github_full_name")
    private String githubFullName;

    @Column(name = "storage_mode", nullable = false, length = 16)
    private String storageMode;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    public StorageMode storageModeEnum() {
        if (storageMode == null) return StorageMode.DATABASE;
        try { return StorageMode.valueOf(storageMode); }
        catch (IllegalArgumentException ex) { return StorageMode.DATABASE; }
    }

    public enum StorageMode { REPO, DATABASE }
}
