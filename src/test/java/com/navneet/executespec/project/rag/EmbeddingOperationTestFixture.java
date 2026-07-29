package com.navneet.executespec.project.rag;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.executioncontext.LlmExecutionOperationClaim;
import com.navneet.executespec.executioncontext.LlmExecutionOperationCoordinator;
import com.navneet.executespec.executioncontext.LlmExecutionOperationDescriptor;
import com.navneet.executespec.executioncontext.LlmExecutionOperationService;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.tool.ExecutionContext;

final class EmbeddingOperationTestFixture {
  private EmbeddingOperationTestFixture() {}

  static EmbeddingOperationRoot.Active active(String contextId, String owner) {
    LlmExecutionReference reference =
        new LlmExecutionReference(contextId, LlmExecutionScope.customer("org-1"));
    LlmExecutionOperationDescriptor descriptor =
        LlmExecutionOperationDescriptor.of(
            "embedding-test:" + contextId, "EMBEDDING_TEST", "embedding-test|" + contextId, 1);
    LlmExecutionOperationClaim claim =
        new LlmExecutionOperationClaim(reference, "llmop-" + contextId, 1, 1, UUID.randomUUID());
    LlmExecutionOperationService operations = mock(LlmExecutionOperationService.class);
    when(operations.claimOrResume(reference, descriptor, owner, Duration.ofMinutes(5)))
        .thenReturn(Optional.of(claim));
    LlmExecutionOperationCoordinator coordinator = new LlmExecutionOperationCoordinator(operations);
    var lease =
        coordinator
            .claimOrResume(reference, descriptor, owner, Duration.ofMinutes(5))
            .orElseThrow();
    ExecutionContext bound =
        coordinator.bind(
            new ExecutionContext(
                "embedding-test", "embedding-test", Path.of("/tmp").toAbsolutePath(), reference),
            lease);
    return new EmbeddingOperationRoot.Active(
        reference, lease, bound.llmExecutionOperationAuthority());
  }
}
