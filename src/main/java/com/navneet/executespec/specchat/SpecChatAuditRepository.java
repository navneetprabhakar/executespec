package com.navneet.executespec.specchat;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Audit-row lookup + upsert for the CLI/Plugin chat endpoint. RL-2026-05-052. */
@Repository
public interface SpecChatAuditRepository extends JpaRepository<SpecChatAudit, String> {

  Optional<SpecChatAudit> findBySessionId(String sessionId);
}
