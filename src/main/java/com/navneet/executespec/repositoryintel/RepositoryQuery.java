package com.navneet.executespec.repositoryintel;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Normalized, bounded request for one or more independently reportable capabilities. */
public record RepositoryQuery(
    String projectId,
    String repositoryId,
    String text,
    Set<RepositoryCapability> capabilities,
    String relativeRoot,
    String filePattern,
    RepositorySnapshotId expectedSnapshot,
    QueryBudget budget,
    boolean providerBackedRetrievalAllowed,
    RepositoryAccessContext accessContext) {

  private static final int MAX_QUERY_CHARS = 4_000;

  public RepositoryQuery {
    projectId = optional(projectId);
    repositoryId = required("repositoryId", repositoryId);
    text = required("text", text);
    if (text.length() > MAX_QUERY_CHARS) {
      throw new IllegalArgumentException("text exceeds " + MAX_QUERY_CHARS + " characters");
    }
    if (capabilities == null || capabilities.isEmpty()) {
      throw new IllegalArgumentException("at least one capability is required");
    }
    capabilities = Set.copyOf(EnumSet.copyOf(capabilities));
    relativeRoot = normalizeRelativePath(relativeRoot);
    filePattern = optional(filePattern);
    if (expectedSnapshot == null) {
      throw new IllegalArgumentException("expectedSnapshot is required");
    }
    if (!repositoryId.equals(expectedSnapshot.repositoryId())) {
      throw new IllegalArgumentException("repositoryId must match expectedSnapshot");
    }
    if (!java.util.Objects.equals(projectId, expectedSnapshot.projectId())) {
      throw new IllegalArgumentException("projectId must match expectedSnapshot");
    }
    budget = budget == null ? QueryBudget.DEFAULT : budget;
    if (accessContext == null) {
      throw new IllegalArgumentException("accessContext is required");
    }
  }

  public String fingerprint() {
    String sortedCapabilities =
        capabilities.stream()
            .sorted(Comparator.comparing(Enum::name))
            .map(Enum::name)
            .collect(Collectors.joining(","));
    return RepositoryIntelligenceIds.sha256(
        projectId,
        repositoryId,
        text,
        sortedCapabilities,
        relativeRoot,
        filePattern,
        expectedSnapshot.stableId(),
        budget.stableKey(),
        Boolean.toString(providerBackedRetrievalAllowed),
        accessContext.stableKey());
  }

  private static String normalizeRelativePath(String value) {
    String cleaned = optional(value);
    if (cleaned == null || ".".equals(cleaned)) return null;
    String normalized = cleaned.replace('\\', '/');
    if (normalized.startsWith("/")
        || normalized.matches("^[A-Za-z]:/.*")
        || normalized.equals("..")
        || normalized.startsWith("../")
        || normalized.contains("/../")
        || normalized.endsWith("/..")) {
      throw new IllegalArgumentException("relativeRoot must remain within the repository");
    }
    return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
  }

  private static String required(String name, String value) {
    String cleaned = optional(value);
    if (cleaned == null) throw new IllegalArgumentException(name + " is required");
    return cleaned;
  }

  private static String optional(String value) {
    String cleaned = RepositoryIntelligenceIds.clean(value);
    return cleaned == null || cleaned.isEmpty() ? null : cleaned;
  }
}
