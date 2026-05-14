package com.navneet.executespec.scoring.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Scoring Revamp Phase A (V197, 2026-05-14) — one row per planner-emitted rubric criterion for a
 * given run. Planner emits these at plan time; {@code RunRubricVerifier} walks them after task
 * execution and flips {@link #status} from {@code PENDING} → {@code PASS / FAIL / UNKNOWN}.
 *
 * <p>The full design (Layer 1 → Layer 3, migration phases A/B/C) lives at {@code
 * docs/plans/scoring-revamp-design.md}. This entity is the storage half of Layer 1 + Layer 2.
 *
 * <p>Phase A is non-breaking: legacy {@code verifiedScore} + {@code rubricScore} keep being
 * computed. The UI doesn't surface these rows yet — that's Phase B.
 */
@Entity
@Table(name = "run_rubric_row")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunRubricRow {

  /** Stable {@link VerificationType} values as plain strings (column is VARCHAR(32)). */
  public static final class VerificationType {
    public static final String SHELL = "shell";
    public static final String FILE_EXISTS = "file-exists";
    public static final String FILE_CONTENT = "file-content";
    public static final String ENDPOINT = "endpoint";
    public static final String PR_STATE = "pr-state";
    public static final String MANUAL = "manual";

    private VerificationType() {}
  }

  /** Stable {@link Status} values. */
  public static final class Status {
    public static final String PENDING = "PENDING";
    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String UNKNOWN = "UNKNOWN";

    private Status() {}
  }

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  @Column(name = "run_id", nullable = false, length = 64)
  private String runId;

  /**
   * Planner-supplied stable id (e.g. {@code "R1"}, {@code "R2"}). Lets the UI render the rubric in
   * the same order the planner emitted it across regenerations / retries.
   */
  @Column(name = "rubric_row_id", nullable = false, length = 32)
  private String rubricRowId;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String criterion;

  /** See {@link VerificationType}. */
  @Column(name = "verification_type", nullable = false, length = 32)
  private String verificationType;

  /**
   * Free-form payload for the verification handler. For {@code shell} this is the command to run;
   * for {@code file-exists} it's the workspace-relative path; for {@code file-content} it's a
   * {@code <path>:<regex>} pair; for {@code endpoint} it's a {@code <method> <path>} pair (relative
   * to the preview URL); for {@code pr-state} it's a state-enum keyword; for {@code manual} it's
   * the prompt to feed the reviewer LLM. Format is per-handler.
   */
  @Column(name = "verification_spec", columnDefinition = "TEXT")
  private String verificationSpec;

  /** BUILD | TESTS | LINT_FORMAT | CONTRACT | FEATURE | DOCS | STRUCTURE | VCS */
  @Column(nullable = false, length = 32)
  private String category;

  /** 1-10. weight × (PASS?1:0) summed for the headline %. */
  @Column(nullable = false)
  @Builder.Default
  private Integer weight = 5;

  /** PENDING (until verified) → PASS | FAIL | UNKNOWN. See {@link Status}. */
  @Column(nullable = false, length = 16)
  @Builder.Default
  private String status = "PENDING";

  /** Human-readable verification evidence (e.g. {@code "exit code 0"}, {@code "file present"}). */
  @Column(columnDefinition = "TEXT")
  private String evidence;

  @Column(name = "verified_at")
  private Instant verifiedAt;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();
}
