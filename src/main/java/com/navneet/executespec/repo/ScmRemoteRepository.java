package com.navneet.executespec.repo;

import java.util.Set;

/** Provider-neutral, credential-free remote repository metadata. */
public record ScmRemoteRepository(
    String provider,
    String immutableId,
    String fullName,
    String displayName,
    String cloneUrl,
    String defaultBranch,
    boolean isPrivate,
    Long sizeKb,
    Set<String> capabilities) {
  public ScmRemoteRepository {
    capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
  }
}
