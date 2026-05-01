package com.navneet.executespec.memory.repository;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.memory.entity.ProjectMemorySnapshot;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectMemorySnapshotRepository
    extends JpaRepository<ProjectMemorySnapshot, String> {

  Optional<ProjectMemorySnapshot> findTopByProjectIdOrderByVersionDesc(String projectId);

  List<ProjectMemorySnapshot> findByProjectIdOrderByVersionDesc(String projectId);
}
