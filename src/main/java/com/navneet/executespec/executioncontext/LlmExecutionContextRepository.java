package com.navneet.executespec.executioncontext;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
interface LlmExecutionContextRepository extends JpaRepository<LlmExecutionContext, String> {

  Optional<LlmExecutionContext> findByIdAndIsolationKey(String id, String isolationKey);

  Optional<LlmExecutionContext>
      findByIsolationKeyAndFeatureAndSourceTypeAndSourceIdAndIdempotencyKey(
          String isolationKey,
          String feature,
          String sourceType,
          String sourceId,
          String idempotencyKey);

  /** Serializes cross-JVM lease and terminal-state mutations for one tenant-scoped context. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT c FROM LlmExecutionContext c "
          + "WHERE c.id = :id AND c.isolationKey = :isolationKey")
  Optional<LlmExecutionContext> findForUpdate(
      @Param("id") String id, @Param("isolationKey") String isolationKey);

  /**
   * Internal recovery sweep lookup; callers already obtained the durable context id from a row FK.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT c FROM LlmExecutionContext c WHERE c.id = :id")
  Optional<LlmExecutionContext> findForUpdateById(@Param("id") String id);
}
