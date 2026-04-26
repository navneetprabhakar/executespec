package com.navneet.executespec.contracts;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContractConsumerRepository extends JpaRepository<ContractConsumer, UUID> {
    /** All consumers declared in this project — drives E2.11 planner injection +
     *  E2.12 breaking-change gate. */
    List<ContractConsumer> findByDeclaredInProjectId(String projectId);

    /** All consumers of a specific contract within a project. */
    List<ContractConsumer> findByContractIdAndDeclaredInProjectId(UUID contractId, String projectId);

    /** Stable identity for the unique constraint — used by E2.10 declare/remove. */
    Optional<ContractConsumer> findByContractIdAndConsumerRepoIdAndDeclaredInProjectId(
            UUID contractId, UUID consumerRepoId, String projectId);

    /** Quick existence check for the breaking-change gate (E2.12). */
    boolean existsByContractIdAndDeclaredInProjectId(UUID contractId, String projectId);
}
