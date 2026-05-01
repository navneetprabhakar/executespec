package com.navneet.executespec.arch.invariants;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ArchInvariantRepository extends JpaRepository<ArchInvariant, UUID> {
  List<ArchInvariant> findByProjectId(String projectId);

  Optional<ArchInvariant> findByProjectIdAndInvariantId(String projectId, String invariantId);
}
