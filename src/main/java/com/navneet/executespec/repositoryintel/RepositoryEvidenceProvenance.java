package com.navneet.executespec.repositoryintel;

/** Evidence origin, including whether it is deterministic or model-derived. */
public enum RepositoryEvidenceProvenance {
  EXACT(true, false),
  HEURISTIC_SYMBOL(true, false),
  AST_EXTRACTED(true, false),
  HEURISTIC_RELATIONSHIP(true, false),
  CODEMAP_PINNED(true, false),
  CODE_SEMANTIC(false, false),
  SEMANTIC_DOCUMENT(false, false),
  LEXICAL_DOCUMENT(true, false),
  ARCHITECTURE_MODEL_DERIVED(false, true),
  ADR_UNVERIFIED(false, false);

  private final boolean deterministic;
  private final boolean modelDerived;

  RepositoryEvidenceProvenance(boolean deterministic, boolean modelDerived) {
    this.deterministic = deterministic;
    this.modelDerived = modelDerived;
  }

  public boolean deterministic() {
    return deterministic;
  }

  public boolean modelDerived() {
    return modelDerived;
  }
}
