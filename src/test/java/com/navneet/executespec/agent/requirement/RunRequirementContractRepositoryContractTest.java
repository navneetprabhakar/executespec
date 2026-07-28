package com.navneet.executespec.agent.requirement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import com.navneet.executespec.agent.requirement.entity.RunRequirementContract;
import com.navneet.executespec.agent.requirement.repository.RunRequirementContractRepository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

class RunRequirementContractRepositoryContractTest {

  @Test
  void repositoryUsesUuidIdentityAndExposesTheFullImmutableLookupTuple() throws Exception {
    assertThat(JpaRepository.class).isAssignableFrom(RunRequirementContractRepository.class);
    var lookup =
        RunRequirementContractRepository.class.getMethod(
            "findByRunIdAndExactPlanFingerprintAndSchemaVersionAndCompilerFingerprint",
            String.class,
            String.class,
            int.class,
            String.class);

    assertThat(lookup.getReturnType()).isEqualTo(Optional.class);
    assertThat(lookup.getGenericReturnType().getTypeName())
        .contains(RunRequirementContract.class.getName());
    var lockedLookup =
        RunRequirementContractRepository.class.getMethod("findByIdForUpdate", UUID.class);
    assertThat(lockedLookup.getReturnType()).isEqualTo(Optional.class);
    assertThat(lockedLookup.getAnnotation(Lock.class).value())
        .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    assertThat(
            java.util.Arrays.stream(RunRequirementContractRepository.class.getGenericInterfaces())
                .map(java.lang.reflect.Type::getTypeName))
        .anyMatch(
            type ->
                type.contains(RunRequirementContract.class.getName())
                    && type.contains(UUID.class.getName()));
  }
}
