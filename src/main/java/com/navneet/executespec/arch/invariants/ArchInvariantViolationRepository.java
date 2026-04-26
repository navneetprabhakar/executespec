package com.navneet.executespec.arch.invariants;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ArchInvariantViolationRepository
        extends JpaRepository<ArchInvariantViolation, UUID> {
    List<ArchInvariantViolation> findByInvariantIdOrderByDetectedAtDesc(UUID invariantId);
}
