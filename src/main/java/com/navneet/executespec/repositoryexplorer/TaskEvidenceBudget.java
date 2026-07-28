package com.navneet.executespec.repositoryexplorer;

/** Independent cap for the evidence handed to a worker. */
public record TaskEvidenceBudget(
    int maxCitations, int maxCharacters, int maxEstimatedTokens, int maxRejectedSummaries) {

  public static final TaskEvidenceBudget DEFAULT = new TaskEvidenceBudget(24, 12_000, 3_000, 16);

  public TaskEvidenceBudget {
    if (maxCitations <= 0) throw new IllegalArgumentException("maxCitations must be positive");
    if (maxCharacters < 512)
      throw new IllegalArgumentException("maxCharacters must be at least 512");
    if (maxEstimatedTokens <= 0) {
      throw new IllegalArgumentException("maxEstimatedTokens must be positive");
    }
    if (maxRejectedSummaries < 0) {
      throw new IllegalArgumentException("maxRejectedSummaries cannot be negative");
    }
  }
}
