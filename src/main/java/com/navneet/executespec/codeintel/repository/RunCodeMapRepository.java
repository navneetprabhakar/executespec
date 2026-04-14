package com.navneet.executespec.codeintel.repository;

import com.navneet.executespec.codeintel.entity.RunCodeMap;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RunCodeMapRepository extends JpaRepository<RunCodeMap, UUID> {
    Optional<RunCodeMap> findByRunId(String runId);
}
