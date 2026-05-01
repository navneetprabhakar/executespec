package com.navneet.executespec.contracts;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ContractDriftEventRepository extends JpaRepository<ContractDriftEvent, UUID> {

  /**
   * Unresolved drift events for a contract (fed by the partial index on V135). Drives E2.9 drift
   * triage UI + the implicit pre-plan check that surfaces drift on the plan-approval gate.
   */
  @Query(
      "SELECT d FROM ContractDriftEvent d WHERE d.contractId = :contractId "
          + "AND d.resolvedAction IS NULL ORDER BY d.detectedAt DESC")
  List<ContractDriftEvent> findUnresolvedByContractId(@Param("contractId") UUID contractId);

  /**
   * Full history for a contract (resolved + unresolved) — the audit log view in the contract-detail
   * UI.
   */
  List<ContractDriftEvent> findByContractIdOrderByDetectedAtDesc(UUID contractId);
}
