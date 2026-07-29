package com.navneet.executespec.repositoryintel;

import java.util.List;

/** Shared deterministic output budget for one repository-intelligence query. */
final class RepositoryEvidenceBudgetCounter {

  private final QueryBudget budget;
  private int hits;
  private int characters;
  private int tokens;

  RepositoryEvidenceBudgetCounter(RepositoryQuery query) {
    if (query == null) throw new IllegalArgumentException("query is required");
    this.budget = query.budget();
    this.characters = RepositoryJsonBudget.reservedBatchCharacters(query);
    this.tokens = Math.max(1, (characters + 3) / 4);
  }

  int add(List<RepositoryEvidence> target, List<RepositoryEvidence> candidates) {
    int accepted = 0;
    for (RepositoryEvidence candidate : candidates) {
      int addedCharacters = candidate.characterCount() + (hits == 0 ? 0 : 1);
      if (hits + 1 > budget.maxTotalHits()
          || characters + addedCharacters > budget.maxCharacters()
          || tokens + Math.max(1, (addedCharacters + 3) / 4) > budget.maxEstimatedTokens()) break;
      target.add(candidate);
      hits++;
      characters += addedCharacters;
      tokens += Math.max(1, (addedCharacters + 3) / 4);
      accepted++;
    }
    return accepted;
  }
}
