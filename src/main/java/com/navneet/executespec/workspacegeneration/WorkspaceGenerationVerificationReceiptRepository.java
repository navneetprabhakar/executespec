package com.navneet.executespec.workspacegeneration;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceGenerationVerificationReceiptRepository
    extends JpaRepository<WorkspaceGenerationVerificationReceipt, UUID> {

  Optional<WorkspaceGenerationVerificationReceipt> findFirstByAttemptIdOrderByCreatedAtDesc(
      UUID attemptId);
}
