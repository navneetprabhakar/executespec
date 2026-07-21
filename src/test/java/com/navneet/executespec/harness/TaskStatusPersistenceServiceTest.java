package com.navneet.executespec.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.repository.AgentTaskRepository;

import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TaskStatusPersistenceServiceTest {

  @Test
  void lockedStatusUpdateRunsInsideProxiedTransaction() {
    AgentTaskRepository repository = mock(AgentTaskRepository.class);
    AgentTask task =
        AgentTask.builder()
            .taskId("12345678_T1")
            .runId("12345678-aaaa-bbbb-cccc-123456789012")
            .status("WORKING")
            .startedAt(Instant.now().minusSeconds(2))
            .recoveryAttempt(3)
            .build();
    AtomicBoolean transactionActiveAtLock = new AtomicBoolean();
    when(repository.findByIdForUpdate("12345678_T1"))
        .thenAnswer(
            invocation -> {
              transactionActiveAtLock.set(
                  TransactionSynchronizationManager.isActualTransactionActive());
              return Optional.of(task);
            });

    TaskStatusPersistenceService service = transactionalProxy(repository);

    TaskStatusPersistenceService.Result result =
        service.persist(task.getRunId(), "T1", "COMPLETE", null, null, task.getRecoveryAttempt());

    assertEquals(TaskStatusPersistenceService.Result.UPDATED, result);
    assertTrue(transactionActiveAtLock.get(), "pessimistic lock must be acquired in a transaction");
    assertEquals("COMPLETE", task.getStatus());
    assertNotNull(task.getLastActivityAt());
    assertNotNull(task.getCompletedAt());
    assertNotNull(task.getDurationMs());
    verify(repository).saveAndFlush(task);
  }

  @Test
  void staleRecoveryGenerationIsFencedBeforeSave() {
    AgentTaskRepository repository = mock(AgentTaskRepository.class);
    AgentTask task =
        AgentTask.builder()
            .taskId("12345678_T1")
            .runId("12345678-aaaa-bbbb-cccc-123456789012")
            .status("WORKING")
            .recoveryAttempt(4)
            .build();
    when(repository.findByIdForUpdate(task.getTaskId())).thenReturn(Optional.of(task));

    TaskStatusPersistenceService.Result result =
        transactionalProxy(repository).persist(task.getRunId(), "T1", "COMPLETE", null, null, 3);

    assertEquals(TaskStatusPersistenceService.Result.STALE_RECOVERY_ATTEMPT, result);
    assertEquals("WORKING", task.getStatus());
  }

  @Test
  void goalDecisionEvidencePersistsInsideProxiedTransaction() {
    // Regression: the fast-fail path (early worker router/provider error) previously reached
    // findByIdForUpdate with no ambient transaction, throwing TransactionRequiredException and
    // silently dropping the V250 goal/continuation evidence. The proxied @Transactional seam must
    // acquire the pessimistic lock inside a live transaction.
    AgentTaskRepository repository = mock(AgentTaskRepository.class);
    AgentTask task =
        AgentTask.builder()
            .taskId("12345678_T1")
            .runId("12345678-aaaa-bbbb-cccc-123456789012")
            .status("FAILED")
            .build();
    AtomicBoolean transactionActiveAtLock = new AtomicBoolean();
    when(repository.findByIdForUpdate("12345678_T1"))
        .thenAnswer(
            invocation -> {
              transactionActiveAtLock.set(
                  TransactionSynchronizationManager.isActualTransactionActive());
              return Optional.of(task);
            });

    TaskStatusPersistenceService.Result result =
        transactionalProxy(repository)
            .persistGoalDecisionJson(
                task.getRunId(), "T1", "{\"state\":\"STOP_FAILED\"}", "{\"decision\":\"STOP\"}");

    assertEquals(TaskStatusPersistenceService.Result.UPDATED, result);
    assertTrue(transactionActiveAtLock.get(), "goal-decision lock must be in a transaction");
    assertEquals("{\"state\":\"STOP_FAILED\"}", task.getGoalDonenessJson());
    assertEquals("{\"decision\":\"STOP\"}", task.getContinuationDecisionJson());
    verify(repository).saveAndFlush(task);
  }

  @Test
  void reopenForContinuationResetsFailedTaskUnderLock() {
    // RR-266 — a FAILED primary attempt whose recovery generation still matches is reopened to
    // PENDING with completion timing cleared, so a selected model/provider handoff rerun can run.
    AgentTaskRepository repository = mock(AgentTaskRepository.class);
    AgentTask task =
        AgentTask.builder()
            .taskId("12345678_T1")
            .runId("12345678-aaaa-bbbb-cccc-123456789012")
            .status("PARTIAL")
            .recoveryAttempt(2)
            .completedAt(Instant.now())
            .durationMs(1234L)
            .build();
    AtomicBoolean transactionActiveAtLock = new AtomicBoolean();
    when(repository.findByIdForUpdate("12345678_T1"))
        .thenAnswer(
            invocation -> {
              transactionActiveAtLock.set(
                  TransactionSynchronizationManager.isActualTransactionActive());
              return Optional.of(task);
            });

    boolean reopened =
        transactionalProxy(repository).reopenForContinuation(task.getRunId(), "T1", 2);

    assertTrue(reopened, "matching FAILED/PARTIAL row must be reopened");
    assertTrue(transactionActiveAtLock.get(), "reopen must acquire the lock in a transaction");
    assertEquals("PENDING", task.getStatus());
    org.junit.jupiter.api.Assertions.assertNull(task.getCompletedAt());
    org.junit.jupiter.api.Assertions.assertNull(task.getDurationMs());
    verify(repository).saveAndFlush(task);
  }

  @Test
  void reopenForContinuationRefusesCancelledTask() {
    // RR-266 — a task a concurrent cancellation moved to CANCELLED must NOT be reopened into a paid
    // rerun: the caller has no authority and the cancellation must stand. (A soft COMPLETE, by
    // contrast, is an escalatable primary outcome and IS reopenable — see reopenForContinuation.)
    AgentTaskRepository repository = mock(AgentTaskRepository.class);
    AgentTask task =
        AgentTask.builder()
            .taskId("12345678_T1")
            .runId("12345678-aaaa-bbbb-cccc-123456789012")
            .status("CANCELLED")
            .recoveryAttempt(2)
            .build();
    when(repository.findByIdForUpdate("12345678_T1")).thenReturn(Optional.of(task));

    boolean reopened =
        transactionalProxy(repository).reopenForContinuation(task.getRunId(), "T1", 2);

    assertFalse(reopened, "a CANCELLED task must not be reopened");
    assertEquals("CANCELLED", task.getStatus());
    verify(repository, org.mockito.Mockito.never())
        .saveAndFlush(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void reopenForContinuationFencesStaleRecoveryGeneration() {
    // RR-266 — a rolling-restart takeover bumped recovery_attempt; the stale executor's reopen must
    // be fenced so it cannot revive a task another JVM now owns.
    AgentTaskRepository repository = mock(AgentTaskRepository.class);
    AgentTask task =
        AgentTask.builder()
            .taskId("12345678_T1")
            .runId("12345678-aaaa-bbbb-cccc-123456789012")
            .status("FAILED")
            .recoveryAttempt(5)
            .build();
    when(repository.findByIdForUpdate("12345678_T1")).thenReturn(Optional.of(task));

    boolean reopened =
        transactionalProxy(repository).reopenForContinuation(task.getRunId(), "T1", 3);

    assertFalse(reopened, "stale recovery generation must be fenced");
    assertEquals("FAILED", task.getStatus());
    verify(repository, org.mockito.Mockito.never())
        .saveAndFlush(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void goalDecisionForMissingRowIsNotFound() {
    AgentTaskRepository repository = mock(AgentTaskRepository.class);
    when(repository.findByIdForUpdate("12345678_T9")).thenReturn(Optional.empty());

    TaskStatusPersistenceService.Result result =
        transactionalProxy(repository)
            .persistGoalDecisionJson("12345678-aaaa-bbbb-cccc-123456789012", "T9", "{}", "{}");

    assertEquals(TaskStatusPersistenceService.Result.ROW_NOT_FOUND, result);
  }

  private static TaskStatusPersistenceService transactionalProxy(AgentTaskRepository repository) {
    TaskStatusPersistenceService target = new TaskStatusPersistenceService(repository, null);
    ProxyFactory factory = new ProxyFactory(target);
    factory.addAdvice(
        new TransactionInterceptor(
            new TestTransactionManager(), new AnnotationTransactionAttributeSource()));
    return (TaskStatusPersistenceService) factory.getProxy();
  }

  private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
