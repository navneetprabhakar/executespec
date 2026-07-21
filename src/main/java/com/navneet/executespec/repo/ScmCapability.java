package com.navneet.executespec.repo;

/** Explicit provider feature contract. UI visibility never implies support beyond this set. */
public enum ScmCapability {
  CATALOG,
  ATTACH,
  SYNC,
  SOURCE_READINESS,
  SOURCE_BROWSE,
  CLOUD_RUN,
  LOCAL_RUN,
  ARCHITECTURE,
  CODE_REVIEW,
  PR_REVIEW,
  WEBHOOK,
  BOOTSTRAP,
  PR_LINK
}
