package com.navneet.executespec.cost;

/** Refuses provider transport when the exact physical route cannot fit an active dollar cap. */
public final class ProjectedCallBudgetExceededException extends RuntimeException {

  private final String scope;
  private final Double capUsd;
  private final Double spentUsd;
  private final Double projectedCallUsd;

  public ProjectedCallBudgetExceededException(String message) {
    super(message);
    this.scope = null;
    this.capUsd = null;
    this.spentUsd = null;
    this.projectedCallUsd = null;
  }

  public ProjectedCallBudgetExceededException(
      String scope, double capUsd, double spentUsd, double projectedCallUsd) {
    super(
        scope
            + " $"
            + String.format("%.2f", capUsd)
            + " projected-call reserve would be exceeded (spent $"
            + String.format("%.4f", spentUsd)
            + ", estimated next call $"
            + String.format("%.4f", projectedCallUsd)
            + ")");
    this.scope = scope;
    this.capUsd = capUsd;
    this.spentUsd = spentUsd;
    this.projectedCallUsd = projectedCallUsd;
  }

  public String scope() {
    return scope;
  }

  public Double capUsd() {
    return capUsd;
  }

  public Double spentUsd() {
    return spentUsd;
  }

  public Double projectedCallUsd() {
    return projectedCallUsd;
  }
}
