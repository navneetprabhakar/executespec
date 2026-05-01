package com.navneet.executespec.codeintel.repository;

import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.codeintel.entity.RunCodeMap;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RunCodeMapRepository extends JpaRepository<RunCodeMap, UUID> {
  Optional<RunCodeMap> findByRunId(String runId);
}
