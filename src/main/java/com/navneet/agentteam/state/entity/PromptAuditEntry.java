package com.navneet.agentteam.state.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "prompt_audit_entry",
       indexes = {
           @Index(name = "idx_audit_prompt",   columnList = "prompt_file, version"),
           @Index(name = "idx_audit_applied",  columnList = "applied_at")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptAuditEntry {

    @Id
    private String id;

    @Column(name = "prompt_file", nullable = false)
    private String promptFile;

    @Column(nullable = false)
    private int version;

    @Column(name = "previous_content", columnDefinition = "TEXT", nullable = false)
    private String previousContent;

    @Column(name = "new_content", columnDefinition = "TEXT", nullable = false)
    private String newContent;

    @Column(name = "diff_summary", columnDefinition = "TEXT", nullable = false)
    private String diffSummary;

    @Column(name = "patterns_matched", columnDefinition = "TEXT", nullable = false)
    private String patternsMatched;   // JSON array of pattern strings

    @Column(name = "source_run_ids", columnDefinition = "TEXT", nullable = false)
    private String sourceRunIds;      // JSON array of run IDs

    @Column(name = "trigger_type", nullable = false)
    private String triggerType;       // SCHEDULED | FAILURE_RATE

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

    @Column(name = "runs_after", nullable = false)
    private int runsAfter = 0;

    @Column(name = "failures_after", nullable = false)
    private int failuresAfter = 0;
}
