package com.navneet.executespec.harness.repository;

import com.navneet.executespec.harness.entity.BackgroundValidation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BackgroundValidationRepository extends JpaRepository<BackgroundValidation, UUID> {

    Optional<BackgroundValidation> findByRunId(String runId);

    /** Stalled validations: RUNNING but started more than {@code staleBefore} ago. */
    @Query("SELECT bv FROM BackgroundValidation bv WHERE bv.status = 'RUNNING' AND bv.startedAt < :staleBefore")
    List<BackgroundValidation> findStalledValidations(@Param("staleBefore") Instant staleBefore);
}
