package com.navneet.executespec.memory.repository;

import com.navneet.executespec.memory.entity.ProjectMemorySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectMemorySnapshotRepository extends JpaRepository<ProjectMemorySnapshot, String> {

    Optional<ProjectMemorySnapshot> findTopByProjectIdOrderByVersionDesc(String projectId);

    List<ProjectMemorySnapshot> findByProjectIdOrderByVersionDesc(String projectId);
}
