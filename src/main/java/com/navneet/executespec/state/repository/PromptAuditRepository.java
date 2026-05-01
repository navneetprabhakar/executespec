package com.navneet.executespec.state.repository;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.state.entity.PromptAuditEntry;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PromptAuditRepository extends JpaRepository<PromptAuditEntry, String> {

  List<PromptAuditEntry> findByPromptFileOrderByVersionDesc(String promptFile);

  List<PromptAuditEntry> findAllByOrderByAppliedAtDesc();

  Optional<PromptAuditEntry> findTopByPromptFileOrderByVersionDesc(String promptFile);

  /** Returns the latest entry for each distinct prompt file — for updateRunOutcome counter. */
  @Query(
      "SELECT e FROM PromptAuditEntry e WHERE e.version = "
          + "(SELECT MAX(e2.version) FROM PromptAuditEntry e2 WHERE e2.promptFile = e.promptFile)")
  List<PromptAuditEntry> findAllLatestPerFile();
}
