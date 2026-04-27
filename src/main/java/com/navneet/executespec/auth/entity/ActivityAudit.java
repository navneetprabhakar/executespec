package com.navneet.executespec.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "activity_audit")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityAudit {

    @Id
    private String id;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "entity_type", nullable = false, length = 32)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private String entityId;

    @Column(name = "org_id")
    private String orgId;

    @Column(name = "actor_id")
    private String actorId;

    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    // ── M-019 (Wave 3) — append-only hash chain (V147) ─────────────────────

    /** SHA-256 hex of the previous chained row's row_hash; '0'×64 for
     *  the genesis row. Null on pre-V147 historical rows. */
    @Column(name = "prev_hash", length = 64)
    private String prevHash;

    /** SHA-256 hex of (prev_hash || canonical_row_content). Null on
     *  pre-V147 historical rows. Verifier walks the chain by recomputing. */
    @Column(name = "row_hash", length = 64)
    private String rowHash;

    /** Strictly-monotonic sequence assigned at write time (V147
     *  Postgres sequence). Drives the verifier's deterministic walk
     *  order — independent of createdAt to avoid ties under load. */
    @Column(name = "chain_seq")
    private Long chainSeq;
}
