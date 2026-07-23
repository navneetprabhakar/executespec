package com.navneet.executespec.repo;

import java.util.List;
import java.util.Set;

/** Provider-neutral brownfield repository read contract. */
public interface ScmRepositoryClient {
  ScmProvider provider();

  Set<ScmCapability> capabilities();

  List<ScmRemoteRepository> listRepositories(String ownerFilter, ScmCredential credential);

  ScmRemoteRepository inspect(String fullName, ScmCredential credential);

  /**
   * Resolve canonical metadata using the immutable provider id when the transport supports it.
   * Legacy adapters may safely fall back to the asserted display slug.
   */
  default ScmRemoteRepository inspect(
      String fullName, String immutableId, ScmCredential credential) {
    return inspect(fullName, credential);
  }

  String branchHeadSha(ScmRemoteRepository repository, String branch, ScmCredential credential);

  ScmSourceInventory inventory(
      ScmRemoteRepository repository, String branch, ScmCredential credential);

  String readFile(
      ScmRemoteRepository repository, String branch, String path, ScmCredential credential);

  default boolean supports(ScmCapability capability) {
    return capabilities().contains(capability);
  }
}
