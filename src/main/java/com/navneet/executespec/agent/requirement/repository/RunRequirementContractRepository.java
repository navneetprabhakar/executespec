package com.navneet.executespec.agent.requirement.repository;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import com.navneet.executespec.agent.requirement.entity.RunRequirementContract;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RunRequirementContractRepository
    extends JpaRepository<RunRequirementContract, UUID> {

  Optional<RunRequirementContract>
      findByRunIdAndExactPlanFingerprintAndSchemaVersionAndCompilerFingerprint(
          String runId, String exactPlanFingerprint, int schemaVersion, String compilerFingerprint);

  /**
   * Locks the exact immutable artifact while its identity and summary are copied into a mutable
   * plan-decision receipt. Immutability prevents updates through ordinary application paths; the
   * row lock additionally prevents a concurrent database writer or operator repair from racing the
   * admission snapshot.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select contract from RunRequirementContract contract where contract.id = :id")
  Optional<RunRequirementContract> findByIdForUpdate(@Param("id") UUID id);
}
