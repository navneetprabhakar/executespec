package com.navneet.executespec.contracts;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ContractRegistryRepository extends JpaRepository<ContractRegistry, UUID> {
  /** All contracts produced by this repo (drives the per-repo coverage panel in E2.3). */
  List<ContractRegistry> findByRepoId(UUID repoId);

  /** Look up by stable identity — used by E2.2 to detect re-runs. */
  Optional<ContractRegistry> findByRepoIdAndName(UUID repoId, String name);

  /**
   * B-14 fix (2026-05-09) — single IN-clause query that replaces N per-repo
   * findByRepoId calls in ProjectExportService.composeBundle. On a 50-repo
   * project this collapses 50 round-trips into 1.
   */
  List<ContractRegistry> findAllByRepoIdIn(List<UUID> repoIds);
}
