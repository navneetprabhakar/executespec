package com.navneet.executespec.repositoryintel;

/** Explicit outcome of one requested capability; an empty hit list is never overloaded. */
public enum RepositoryCapabilityState {
  AVAILABLE_HIT,
  AVAILABLE_NO_MATCH,
  UNAVAILABLE,
  STALE_REJECTED,
  PARTIAL,
  FAILED
}
