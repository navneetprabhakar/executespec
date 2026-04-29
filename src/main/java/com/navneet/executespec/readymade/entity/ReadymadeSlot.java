package com.navneet.executespec.readymade.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * R-327 — a parameter the user fills before instantiating a
 * {@link ReadymadeSolution}.  The slot's {@link #type} determines the
 * UI control (text input, dropdown, secret field, repo picker, etc.)
 * and the validator run on submit.
 *
 * <p>Slot values are substituted into the variant's {@code spec_template}
 * by {@code SpecComposer} (W5) — so a slot named {@code currency} with
 * user-supplied value {@code "USD"} becomes {@code USD} wherever the
 * template has {@code {{currency}}}.
 */
@Entity
@Table(
    name = "readymade_slot",
    uniqueConstraints = @UniqueConstraint(columnNames = {"solution_id", "name"})
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReadymadeSlot {

    /** Slot input shape — drives both the rendered UI control and
     *  the {@code SlotValidator} (W5) check. */
    public enum Type {
        TEXT,
        SELECT,
        SECRET,
        REPO_PICKER,
        FILE_PATH,
        NUMBER,
        BOOLEAN
    }

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "solution_id", nullable = false)
    private UUID solutionId;

    /** Stable identifier used in {@code {{slot_name}}} placeholders. */
    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Type type;

    @Column(nullable = false)
    @Builder.Default
    private boolean required = false;

    @Column(name = "default_value", columnDefinition = "TEXT")
    private String defaultValue;

    /** For {@link Type#SELECT}: JSON array of allowed string values
     *  (e.g. {@code ["USD","EUR","INR","GBP"]}). NULL for other types. */
    @Column(columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String options;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;
}
