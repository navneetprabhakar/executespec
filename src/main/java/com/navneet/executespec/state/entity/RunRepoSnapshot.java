package com.navneet.executespec.state.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-run repo snapshot — the exact commit SHA a linked repo was cloned from
 * when the run started. Used by {@code RunSyncService.syncToOriginalCommit} to
 * reset the workspace to the state the run actually executed against, not to
 * current HEAD of the configured branch.
 *
 * <p>Populated by {@code WorkspaceInitService.cloneMultipleRepos} right after each
 * clone. Rows cascade-delete with their parent {@code pipeline_run} (V100).
 */
@Entity
@Table(name = "run_repo_snapshot")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunRepoSnapshot {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "run_id", nullable = false, length = 255)
    private String runId;

    @Column(name = "repo_id", columnDefinition = "uuid")
    private UUID repoId;

    @Column(name = "repo_full_name", nullable = false, length = 255)
    private String repoFullName;

    @Column(nullable = false, length = 255)
    private String branch;

    @Column(name = "commit_sha", nullable = false, length = 40)
    private String commitSha;

    @Column(name = "cloned_at", nullable = false)
    @Builder.Default
    private Instant clonedAt = Instant.now();
}
