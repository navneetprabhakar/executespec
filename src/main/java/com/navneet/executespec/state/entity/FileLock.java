package com.navneet.executespec.state.entity;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-run advisory lock over a workspace-relative file path.
 *
 * <p><b>Identity is {@code (runId, filePath)}</b> (RR-1544, migration {@code V309}). {@code
 * filePath} alone was the primary key, and since every path is workspace-RELATIVE that made the
 * table globally shared: two concurrent runs — including runs in different orgs — touching {@code
 * src/index.ts} collided, a claim was refused by an unrelated tenant's lock, the row was silently
 * reattributed, and {@code getOwner} disclosed a foreign run's taskId.
 */
@Entity
@Table(name = "file_lock")
@IdClass(FileLock.FileLockId.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileLock {

  /** Owning run — part of the primary key; a lock never spans runs. */
  @Id private String runId;

  /** Path relative to the run's workspace root — part of the primary key. */
  @Id private String filePath;

  private String taskId; // which worker owns this file
  private Instant claimedAt;

  /** Composite primary key: a file path is only meaningful within its run. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class FileLockId implements Serializable {
    private String runId;
    private String filePath;

    @Override
    public boolean equals(Object other) {
      if (this == other) return true;
      if (!(other instanceof FileLockId id)) return false;
      return Objects.equals(runId, id.runId) && Objects.equals(filePath, id.filePath);
    }

    @Override
    public int hashCode() {
      return Objects.hash(runId, filePath);
    }
  }
}
