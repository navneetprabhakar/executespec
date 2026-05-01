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
}
