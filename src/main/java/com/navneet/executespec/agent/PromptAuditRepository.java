package com.navneet.executespec.agent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PromptAuditRepository extends JpaRepository<PromptAudit, Long> {

    List<PromptAudit> findByRunId(UUID runId);

    List<PromptAudit> findByPromptHash(String promptHash);
}
