package com.navneet.executespec.state.repository;

import com.navneet.executespec.state.entity.RunRepoSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RunRepoSnapshotRepository extends JpaRepository<RunRepoSnapshot, UUID> {
    List<RunRepoSnapshot> findByRunId(String runId);
}
