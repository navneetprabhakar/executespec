package com.navneet.executespec.contracts;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * E2.1 — versioned content snapshot for a {@link ContractRegistry} row.
 *
 * <p>Two storage strategies:
 * <ul>
 *   <li>{@link #inlineContent} (JSONB) for contracts &lt;~256KB — fastest read,
 *       no extra round-trip</li>
 *   <li>{@link #contentPointer} (GCS path) for larger contracts —
 *       avoids JSONB write amplification on multi-MB OpenAPI bundles</li>
 * </ul>
 * Exactly one of the two should be non-null per row. The choice is made
 * by the writer (E2.2 detector pipeline) at insert time and is not
 * runtime-mutable.
 */
@Entity
@Table(name = "contract_version", uniqueConstraints =
        @UniqueConstraint(columnNames = {"contract_id", "registry_version"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractVersion {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @Column(name = "registry_version", nullable = false)
    private Integer registryVersion;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "content_pointer", columnDefinition = "TEXT")
    private String contentPointer;

    @Column(name = "inline_content", columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String inlineContent;

    @Column(name = "detected_at", nullable = false)
    @Builder.Default
    private Instant detectedAt = Instant.now();

    /** When known, the run that first persisted this version. Useful for
     *  attributing per-run cost/latency to contract evolution. */
    @Column(name = "created_by_run_id")
    private UUID createdByRunId;
}
