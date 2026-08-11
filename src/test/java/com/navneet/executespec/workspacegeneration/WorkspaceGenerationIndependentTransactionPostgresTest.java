package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.integration.PostgresIntegrationBase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class WorkspaceGenerationIndependentTransactionPostgresTest extends PostgresIntegrationBase {

  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;

  @BeforeEach
  void createProbeTable() {
    jdbc.execute("DROP TABLE IF EXISTS workspace_generation_tx_probe");
    jdbc.execute("CREATE TABLE workspace_generation_tx_probe(phase TEXT PRIMARY KEY)");
  }

  @Test
  void rollbackEvidenceSurvivesAnOuterTransactionRollback() {
    UUID attemptId = UUID.randomUUID();
    UUID workspaceToken = UUID.randomUUID();
    WorkspaceGenerationAttemptRepository attempts =
        mock(WorkspaceGenerationAttemptRepository.class);
    CorrectionWorkspaceTransaction workspaces = mock(CorrectionWorkspaceTransaction.class);
    WorkspaceIgnoredArtifactGuard ignored = mock(WorkspaceIgnoredArtifactGuard.class);
    CorrectionWorkspaceTransaction.Checkpoint checkpoint =
        new CorrectionWorkspaceTransaction.Checkpoint(
            Path.of("/tmp/tx-proof"),
            List.of(
                new CorrectionWorkspaceTransaction.RepositoryCheckpoint(
                    Path.of("/tmp/tx-proof"), "1".repeat(40), "2".repeat(40))));
    WorkspaceGenerationAttempt attempt =
        WorkspaceGenerationAttempt.builder()
            .id(attemptId)
            .state(WorkspaceGenerationState.MUTATING)
            .beforeGeneration("a".repeat(64))
            .ignoredArtifactBaselineJson("{}")
            .build();
    when(attempts.findById(attemptId)).thenReturn(Optional.of(attempt));
    when(workspaces.diffEvidence(eq(checkpoint), any()))
        .thenReturn(
            new CorrectionWorkspaceTransaction.DiffEvidence(
                List.of("0:main.py"), "b".repeat(64), "patch", 5, 5, false, false));
    when(attempts.beginRollback(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            ignoredInvocation -> {
              jdbc.update("INSERT INTO workspace_generation_tx_probe(phase) VALUES ('intent')");
              return 1;
            });
    when(workspaces.currentGeneration(eq(checkpoint), any())).thenReturn("a".repeat(64));
    when(attempts.markRolledBack(any(), any(), any(), any(), any()))
        .thenAnswer(
            ignoredInvocation -> {
              jdbc.update("INSERT INTO workspace_generation_tx_probe(phase) VALUES ('resolved')");
              return 1;
            });
    WorkspaceGenerationRollbackService service =
        new WorkspaceGenerationRollbackService(
            attempts, workspaces, ignored, new ObjectMapper(), transactionManager);
    TransactionTemplate outer = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                outer.executeWithoutResult(
                    ignoredTransaction -> {
                      service.rollback(
                          attemptId,
                          workspaceToken,
                          checkpoint,
                          () -> {},
                          "TASK_VERIFICATION_REJECTED",
                          "rejected");
                      throw new IllegalStateException("outer failed after rollback");
                    }))
        .hasMessage("outer failed after rollback");

    assertThat(
            jdbc.queryForList(
                "SELECT phase FROM workspace_generation_tx_probe ORDER BY phase", String.class))
        .containsExactly("intent", "resolved");
  }
}
