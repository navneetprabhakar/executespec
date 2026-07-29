package com.navneet.executespec.repositoryintel;

/**
 * Read-only boundary for repository evidence. Implementations must not trigger indexing or LLMs.
 */
@FunctionalInterface
public interface RepositoryIntelligencePort {

  RepositoryEvidenceBatch query(RepositoryQuery query);
}
