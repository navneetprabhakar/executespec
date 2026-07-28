package com.navneet.executespec.repositoryintel;

/** Freshness of evidence relative to the repository snapshot requested by the caller. */
public enum RepositoryFreshness {
  CURRENT,
  STALE,
  UNKNOWN,
  NOT_APPLICABLE
}
