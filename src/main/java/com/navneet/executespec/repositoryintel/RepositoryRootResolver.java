package com.navneet.executespec.repositoryintel;

/**
 * Resolves an already-materialized repository through the canonical access-control and workspace
 * boundary. A resolver must never return a caller-supplied raw path.
 */
@FunctionalInterface
public interface RepositoryRootResolver {

  AuthorizedRepositoryHandle resolve(RepositoryQuery query);

  /**
   * Resolve an authority target before an inventory-bound snapshot exists. Implementations that
   * support trusted snapshot acquisition override this method; the default preserves the existing
   * functional-interface contract for isolated adapters and tests.
   */
  default AuthorizedRepositoryHandle resolve(RepositoryTarget target) {
    throw new UnsupportedOperationException("target resolution is not supported");
  }
}
