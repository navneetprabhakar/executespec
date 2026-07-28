package com.navneet.executespec.repositoryintel;

import java.nio.file.Path;

/** Resolves an already-materialized repository root without cloning or generating anything. */
@FunctionalInterface
public interface RepositoryRootResolver {

  Path resolve(RepositoryQuery query);
}
