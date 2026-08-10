package com.navneet.executespec.workspacegeneration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceGenerationVerificationReceiptRepository
    extends JpaRepository<WorkspaceGenerationVerificationReceipt, UUID> {

  List<WorkspaceGenerationVerificationReceipt> findByAttemptIdOrderByCreatedAtAsc(UUID attemptId);

  Optional<WorkspaceGenerationVerificationReceipt> findFirstByAttemptIdOrderByCreatedAtDesc(
      UUID attemptId);
}
