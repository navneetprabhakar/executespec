package com.navneet.executespec.readymade.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * R-327 — concrete (language, framework, options) cell of a
 * {@link ReadymadeSolution}.  Runtime variant resolution (W3 of the
 * Readymade Solutions plan) picks one variant per (solution, user-repo)
 * by reading the repo's detected stack and matching against
 * {@link #language} + {@link #framework} + {@link #options}.
 *
 * <p>If a solution supports e.g. Login-with-Google across Express,
 * Fastify, FastAPI, Spring Boot — there are 4 rows in this table for
 * that solution, one per framework.
 */
@Entity
@Table(name = "readymade_variant")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReadymadeVariant {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "solution_id", nullable = false)
    private UUID solutionId;

    @Column(nullable = false, length = 32)
    private String language;

    @Column(nullable = false, length = 64)
    private String framework;

    /** JSON object of variant-specific options
     *  (e.g. {@code {"provider":"google","db":"postgres"}}). */
    @Column(columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String options;

    /** Spec text with {@code {{slot_name}}} placeholders. The
     *  {@code SpecComposer} (W5) substitutes user-supplied slot values
     *  into this template before submitting to the V2 pipeline. */
    @Column(name = "spec_template", nullable = false, columnDefinition = "TEXT")
    private String specTemplate;

    /** Optional reference into {@code prompts/scaffolds/<key>.md}. When
     *  set, the V2 worker also loads that scaffold during execution
     *  (existing behaviour from {@code SequentialExecutorV2}). */
    @Column(name = "scaffold_key", length = 64)
    private String scaffoldKey;

    /** Per-variant cost override; falls back to the parent
     *  solution's est_credits_low/high when null. */
    @Column(name = "est_credits_low", precision = 10, scale = 2)
    private BigDecimal estCreditsLow;

    @Column(name = "est_credits_high", precision = 10, scale = 2)
    private BigDecimal estCreditsHigh;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
