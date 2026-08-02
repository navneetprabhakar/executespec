package com.navneet.executespec.cost;

/** Refuses provider transport when the exact physical route cannot fit an active dollar cap. */
public final class ProjectedCallBudgetExceededException extends RuntimeException {

  public ProjectedCallBudgetExceededException(String message) {
    super(message);
  }
}
