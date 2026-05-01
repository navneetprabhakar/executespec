package com.navneet.executespec.agent;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link PromptAudit} — tracks SHA-256 hash fingerprints of assembled prompts for
 * version auditing via {@link PromptAssembler}.
 *
 * <p>Distinct from {@code state.repository.PromptAuditRepository} which tracks {@code
 * PromptAuditEntry} (prompt file version history for the optimiser).
 */
@Repository
public interface PromptHashAuditRepository extends JpaRepository<PromptAudit, Long> {

  List<PromptAudit> findByRunId(String runId);

  List<PromptAudit> findByPromptHash(String promptHash);
}
