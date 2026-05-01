package com.navneet.executespec.contracts;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ContractVersionRepository extends JpaRepository<ContractVersion, UUID> {
  /** Latest version (by registry_version DESC). */
  Optional<ContractVersion> findFirstByContractIdOrderByRegistryVersionDesc(UUID contractId);

  /** Specific version. */
  Optional<ContractVersion> findByContractIdAndRegistryVersion(
      UUID contractId, Integer registryVersion);

  /** Full history — used by the contract-detail UI in E2.4 + drift UX in E2.9. */
  List<ContractVersion> findByContractIdOrderByRegistryVersionDesc(UUID contractId);
}
