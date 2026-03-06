package com.navneet.agentteam.state.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Tracks merge conflict occurrences and their resolution strategy.
 * Used for analytics feedback into the planner prompt.
 */
@Entity
@Table(name = "merge_conflict")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MergeConflict {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 36)
    private String runId;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    /** TEXTUAL, SEMANTIC, DEPENDENCY */
    @Column(name = "conflict_type", nullable = false, length = 32)
    private String conflictType;

    /** DETERMINISTIC, AUTO_HAIKU, AUTO_SONNET, MANUAL, FAILED */
    @Column(name = "resolution", nullable = false, length = 32)
    private String resolution;

    @Column(name = "resolver_model", length = 64)
    private String resolverModel;

    @Column(name = "resolver_tokens_in")
    private Integer resolverTokensIn;

    @Column(name = "resolver_tokens_out")
    private Integer resolverTokensOut;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at")
    @Builder.Default
    private Instant createdAt = Instant.now();
}
