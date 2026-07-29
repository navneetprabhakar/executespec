package com.navneet.executespec.project.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import com.navneet.executespec.project.entity.ProjectRepo;

/** Canonical server-owned workspace identity shared by code indexing and repository reads. */
public final class CodeIndexWorkspaceIdentity {

  private CodeIndexWorkspaceIdentity() {}

  public static String workspaceId(String projectId, ProjectRepo repo) {
    if (projectId == null || projectId.isBlank()) {
      throw new IllegalArgumentException("project identity is required");
    }
    if (repo == null || repo.getId() == null) {
      throw new IllegalArgumentException("repository identity is required");
    }
    String identity = projectId.trim() + "\u0000" + repo.getId() + "\u0000" + sourceRevision(repo);
    return "projchat-" + repo.getId() + "-" + sha256(identity);
  }

  public static String sourceRevision(ProjectRepo repo) {
    if (repo == null) throw new IllegalArgumentException("repository is required");
    if (repo.getLastSyncedSha() != null && !repo.getLastSyncedSha().isBlank()) {
      return repo.getLastSyncedSha().trim();
    }
    if (repo.getSourceRevision() != null && !repo.getSourceRevision().isBlank()) {
      return repo.getSourceRevision().trim();
    }
    return "UNPINNED";
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
