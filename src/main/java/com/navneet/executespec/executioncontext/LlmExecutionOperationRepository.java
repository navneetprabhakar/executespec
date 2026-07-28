package com.navneet.executespec.executioncontext;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
interface LlmExecutionOperationRepository extends JpaRepository<LlmExecutionOperation, String> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<LlmExecutionOperation>
      findFirstByLlmExecutionContextIdAndOperationKeyOrderByInvocationDesc(
          String contextId, String operationKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT o FROM LlmExecutionOperation o WHERE o.id = :id"
          + " AND o.llmExecutionContextId = :contextId")
  Optional<LlmExecutionOperation> findForUpdate(
      @Param("id") String id, @Param("contextId") String contextId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT o FROM LlmExecutionOperation o WHERE o.llmExecutionContextId = :contextId"
          + " AND o.status IN :statuses ORDER BY o.operationKey, o.invocation")
  List<LlmExecutionOperation> findNonTerminalForUpdate(
      @Param("contextId") String contextId,
      @Param("statuses") List<LlmExecutionOperation.Status> statuses);

  @Query(
      "SELECT o.id AS operationId, o.llmExecutionContextId AS contextId"
          + " FROM LlmExecutionOperation o WHERE o.status = :status"
          + " AND (o.leaseExpiresAt IS NULL OR o.leaseExpiresAt <= :now)"
          + " AND (o.notReclaimBefore IS NULL OR o.notReclaimBefore <= :now)"
          + " ORDER BY o.llmExecutionContextId, o.id")
  List<CancellationCandidate> findDueCancellationCandidates(
      @Param("status") LlmExecutionOperation.Status status,
      @Param("now") java.time.Instant now,
      Pageable pageable);

  interface CancellationCandidate {
    String getOperationId();

    String getContextId();
  }
}
