package com.navneet.executespec.state.repository;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.state.entity.RunRepoSnapshot;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RunRepoSnapshotRepository extends JpaRepository<RunRepoSnapshot, UUID> {
  List<RunRepoSnapshot> findByRunId(String runId);
}
