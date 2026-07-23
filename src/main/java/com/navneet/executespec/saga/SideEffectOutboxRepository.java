package com.navneet.executespec.saga;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SideEffectOutboxRepository extends JpaRepository<SideEffectOutboxEntry, UUID> {
  List<SideEffectOutboxEntry> findByRunId(String runId);
}
