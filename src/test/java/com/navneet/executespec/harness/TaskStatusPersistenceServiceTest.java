package com.navneet.executespec.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

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
  void rollbackReopenTransitionResetsPartialTaskUnderLock() {
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

    TaskStatusPersistenceService.Result result =
        transactionalProxy(repository)
            .publishWorkspaceGenerationTransition(
                task.getRunId(), "T1", "PENDING", "PARTIAL", 2, null);

    assertEquals(TaskStatusPersistenceService.Result.UPDATED, result);
    assertTrue(transactionActiveAtLock.get(), "reopen must acquire the lock in a transaction");
    assertEquals("PENDING", task.getStatus());
    org.junit.jupiter.api.Assertions.assertNull(task.getCompletedAt());
    org.junit.jupiter.api.Assertions.assertNull(task.getDurationMs());
    verify(repository).saveAndFlush(task);
  }

  @Test
  void cancelledRunBlocksRollbackReopenTransition() {
    AgentTaskRepository tasks = mock(AgentTaskRepository.class);
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    String runId = "12345678-aaaa-bbbb-cccc-123456789012";
    PipelineRun cancelled = PipelineRun.builder().runId(runId).status("CANCELLED").build();
    AgentTask task =
        AgentTask.builder()
            .taskId("12345678_T1")
            .runId(runId)
            .status("WORKING")
            .startedAt(java.time.Instant.now().minusSeconds(5))
            .build();
    when(runs.findByIdForTaskContinuation(runId)).thenReturn(Optional.of(cancelled));
    when(tasks.findByIdForUpdate("12345678_T1")).thenReturn(Optional.of(task));
    TaskStatusPersistenceService service = new TaskStatusPersistenceService(tasks, null, runs);

    TaskStatusPersistenceService.Result result =
        service.publishWorkspaceGenerationTransition(runId, "T1", "PENDING", "PARTIAL", 0, null);

    assertEquals(TaskStatusPersistenceService.Result.CANCELLED_WINS, result);
    assertEquals("CANCELLED", task.getStatus());
    org.junit.jupiter.api.Assertions.assertNotNull(task.getCompletedAt());
    verify(tasks).saveAndFlush(task);
  }

  @Test
  void rollbackReopenTransitionRefusesCancelledTask() {
    AgentTaskRepository repository = mock(AgentTaskRepository.class);
    AgentTask task =
        AgentTask.builder()
            .taskId("12345678_T1")
            .runId("12345678-aaaa-bbbb-cccc-123456789012")
            .status("CANCELLED")
            .recoveryAttempt(2)
            .build();
    when(repository.findByIdForUpdate("12345678_T1")).thenReturn(Optional.of(task));

    TaskStatusPersistenceService.Result result =
        transactionalProxy(repository)
            .publishWorkspaceGenerationTransition(
                task.getRunId(), "T1", "PENDING", "PARTIAL", 2, null);

    assertEquals(TaskStatusPersistenceService.Result.CANCELLED_WINS, result);
    assertEquals("CANCELLED", task.getStatus());
    verify(repository, org.mockito.Mockito.never())
        .saveAndFlush(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void workspaceRollbackPublicationIsIdempotentButFencesInterveningTaskState() {
    AgentTaskRepository repository = mock(AgentTaskRepository.class);
    AgentTask task =
        AgentTask.builder()
            .taskId("12345678_T1")
            .runId("12345678-aaaa-bbbb-cccc-123456789012")
            .status("WORKING")
            .recoveryAttempt(2)
            .build();
    when(repository.findByIdForUpdate(task.getTaskId())).thenReturn(Optional.of(task));
    TaskStatusPersistenceService service = transactionalProxy(repository);

    assertEquals(
        TaskStatusPersistenceService.Result.UPDATED,
        service.publishWorkspaceGenerationTransition(
            task.getRunId(), "T1", "PARTIAL", "WORKING", 2, "runtime red"));
    assertEquals("PARTIAL", task.getStatus());
    assertEquals(
        TaskStatusPersistenceService.Result.UPDATED,
        service.publishWorkspaceGenerationTransition(
            task.getRunId(), "T1", "PARTIAL", "WORKING", 2, "runtime red"));

    task.setStatus("COMPLETE");
    assertEquals(
        TaskStatusPersistenceService.Result.TERMINAL_OVERWRITE,
        service.publishWorkspaceGenerationTransition(
            task.getRunId(), "T1", "PARTIAL", "WORKING", 2, "runtime red"));
    assertEquals("COMPLETE", task.getStatus());
  }

  @Test
  void workspaceAcceptancePublicationCompletesTheExactWorkingTaskIdempotently() {
    AgentTaskRepository repository = mock(AgentTaskRepository.class);
    AgentTask task =
        AgentTask.builder()
            .taskId("12345678_T1")
            .runId("12345678-aaaa-bbbb-cccc-123456789012")
            .status("WORKING")
            .recoveryAttempt(2)
            .startedAt(Instant.now().minusSeconds(2))
            .build();
    when(repository.findByIdForUpdate(task.getTaskId())).thenReturn(Optional.of(task));
    TaskStatusPersistenceService service = transactionalProxy(repository);

    assertEquals(
        TaskStatusPersistenceService.Result.UPDATED,
        service.publishWorkspaceGenerationTransition(
            task.getRunId(), "T1", "COMPLETE", "WORKING", 2, null));
    assertEquals("COMPLETE", task.getStatus());
    assertTrue(task.getCompletedAt() != null);
    assertTrue(task.getDurationMs() != null && task.getDurationMs() >= 0);
    assertEquals(
        TaskStatusPersistenceService.Result.UPDATED,
        service.publishWorkspaceGenerationTransition(
            task.getRunId(), "T1", "COMPLETE", "WORKING", 2, null));
  }

  @Test
  void workspaceRollbackPublicationFencesRecoveryGeneration() {
    AgentTaskRepository repository = mock(AgentTaskRepository.class);
    AgentTask task =
        AgentTask.builder()
            .taskId("12345678_T1")
            .runId("12345678-aaaa-bbbb-cccc-123456789012")
            .status("WORKING")
            .recoveryAttempt(3)
            .build();
    when(repository.findByIdForUpdate(task.getTaskId())).thenReturn(Optional.of(task));

    TaskStatusPersistenceService.Result result =
        transactionalProxy(repository)
            .publishWorkspaceGenerationTransition(
                task.getRunId(), "T1", "PENDING", "WORKING", 2, null);

    assertEquals(TaskStatusPersistenceService.Result.STALE_RECOVERY_ATTEMPT, result);
    assertEquals("WORKING", task.getStatus());
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

  // RR-265 — the continuation-action append takes the pessimistic row lock inside the proxied
  // transaction (previously the non-transactional executor called findByIdForUpdate directly and
  // TransactionRequiredException was swallowed, silently dropping the audit row), appends
  // atomically onto the existing array, and assigns the durable attempt index.
  @Test
  void continuationActionAppendRunsInsideProxiedTransactionAndPreservesHistory() {
    AgentTaskRepository repository = mock(AgentTaskRepository.class);
    AgentTask task =
        AgentTask.builder()
            .taskId("12345678_T1")
            .runId("12345678-aaaa-bbbb-cccc-123456789012")
            .status("PARTIAL")
            .recoveryAttempt(3)
            .continuationActionsJson(
                "[{\"status\":\"EXECUTED\",\"decision\":\"CHANGE_PROVIDER\",\"attempt\":0}]")
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
            .appendContinuationAction(
                task.getRunId(),
                "T1",
                "{\"status\":\"APPROVED\",\"decision\":\"STEP_UP_MODEL\"}",
                3);

    assertEquals(TaskStatusPersistenceService.Result.UPDATED, result);
    assertTrue(transactionActiveAtLock.get(), "row lock must run in a transaction");
    // Existing EXECUTED row preserved; the new APPROVED row appended with attempt index 1.
    assertTrue(task.getContinuationActionsJson().contains("CHANGE_PROVIDER"));
    assertTrue(task.getContinuationActionsJson().contains("STEP_UP_MODEL"));
    assertTrue(task.getContinuationActionsJson().contains("\"attempt\":1"));
    verify(repository).saveAndFlush(task);
  }

  // RR-265 — a stale executor (recovery generation mismatch) must not clobber a newer document.
  @Test
  void continuationActionAppendIsFencedByRecoveryGeneration() {
    AgentTaskRepository repository = mock(AgentTaskRepository.class);
    AgentTask task =
        AgentTask.builder()
            .taskId("12345678_T1")
            .runId("12345678-aaaa-bbbb-cccc-123456789012")
            .status("PARTIAL")
            .recoveryAttempt(5)
            .build();
    when(repository.findByIdForUpdate("12345678_T1")).thenReturn(Optional.of(task));

    TaskStatusPersistenceService.Result result =
        transactionalProxy(repository)
            .appendContinuationAction(
                task.getRunId(),
                "T1",
                "{\"status\":\"EXECUTED\",\"decision\":\"CHANGE_PROVIDER\"}",
                2);

    assertEquals(TaskStatusPersistenceService.Result.STALE_RECOVERY_ATTEMPT, result);
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
