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
 * R-327 — abstract entry in the Readymade Solutions library.
 *
 * <p>Each row is one library item ("Login with Google", "Stripe checkout",
 * "OpenAI chat") whose concrete code generation lives in
 * {@link ReadymadeVariant} rows referencing this row's id.  Slots
 * (user-fillable parameters) live in {@link ReadymadeSlot}; dependency
 * graph in {@link ReadymadeDependency}.
 *
 * <p>See {@code docs/plans/pending-readymade-solutions-plan.md} for the
 * full product shape; this entity is the W1 schema only.
 */
@Entity
@Table(name = "readymade_solution")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReadymadeSolution {

    /** PRIMITIVE = single-solution drop-in; STACK = composition of others;
     *  INTEGRATION = wires an external service into existing code. */
    public enum Kind { PRIMITIVE, STACK, INTEGRATION }

    /** Tier-gating shape: any tier can SEE the card, but FREE/PRO see
     *  PRO+ entries with a lock icon (upgrade-CTA path). */
    public enum Tier { FREE, PRO, TEAM, ENTERPRISE }

    /** FIRST_PARTY = curated by ExecuteSpec; COMMUNITY = user-submitted
     *  (W7 moderation pipeline). */
    public enum Source { FIRST_PARTY, COMMUNITY }

    public enum Status { LISTED, HIDDEN, ARCHIVED }

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, unique = true, length = 64)
    private String slug;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(length = 255)
    private String tagline;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 64)
    private String category;

    @Column(name = "macro_group", nullable = false, length = 32)
    private String macroGroup;

    /** JSON array of tag strings (e.g. {@code ["auth","oauth","social"]}). */
    @Column(columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String tags;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private Tier tier = Tier.PRO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private Source source = Source.FIRST_PARTY;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private Status status = Status.LISTED;

    @Column(name = "icon_url", columnDefinition = "TEXT")
    private String iconUrl;

    @Column(name = "thumbnail_url", columnDefinition = "TEXT")
    private String thumbnailUrl;

    /** Link to a real demo PR — "this is what your PR will look like." */
    @Column(name = "sample_pr_url", columnDefinition = "TEXT")
    private String samplePrUrl;

    @Column(name = "est_files_low")
    private Integer estFilesLow;

    @Column(name = "est_files_high")
    private Integer estFilesHigh;

    @Column(name = "est_credits_low", precision = 10, scale = 2)
    private BigDecimal estCreditsLow;

    @Column(name = "est_credits_high", precision = 10, scale = 2)
    private BigDecimal estCreditsHigh;

    /** Set when {@link #source} = COMMUNITY; NULL for FIRST_PARTY. */
    @Column(name = "author_user_id", length = 36)
    private String authorUserId;

    @Column(name = "author_org_id", length = 36)
    private String authorOrgId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
