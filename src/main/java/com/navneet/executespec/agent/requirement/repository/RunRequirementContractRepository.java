package com.navneet.executespec.agent.requirement.repository;

import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.agent.requirement.entity.RunRequirementContract;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RunRequirementContractRepository
    extends JpaRepository<RunRequirementContract, UUID> {

  Optional<RunRequirementContract>
      findByRunIdAndExactPlanFingerprintAndSchemaVersionAndCompilerFingerprint(
          String runId, String exactPlanFingerprint, int schemaVersion, String compilerFingerprint);
}
