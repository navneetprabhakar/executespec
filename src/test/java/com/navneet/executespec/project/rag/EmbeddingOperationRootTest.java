package com.navneet.executespec.project.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.executioncontext.LlmExecutionOperationClaim;
import com.navneet.executespec.executioncontext.LlmExecutionOperationCoordinator;
import com.navneet.executespec.executioncontext.LlmExecutionOperationDescriptor;
import com.navneet.executespec.executioncontext.LlmExecutionOperationService;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EmbeddingOperationRootTest {

  private final LlmExecutionPolicyFactory policyFactory = mock(LlmExecutionPolicyFactory.class);
  private final LlmExecutionOperationService operations = mock(LlmExecutionOperationService.class);
  private final LlmExecutionOperationCoordinator coordinator =
      new LlmExecutionOperationCoordinator(operations);
  private final EmbeddingOperationRoot root =
      new EmbeddingOperationRoot(policyFactory, coordinator);
  private final LlmExecutionReference reference =
      new LlmExecutionReference("ctx-embedding", LlmExecutionScope.customer("org-1"));
  private final EmbeddingOperationRoot.Request request =
      new EmbeddingOperationRoot.Request(
          "project-1",
          "ProjectCodeEmbedding",
          "PROJECT_REPOSITORY",
          "repo-1:sha-1",
          "actor-1",
          "project-code-index:project-1:repo-1:sha-1",
          "PROJECT_CODE_INDEX",
          "project-1|repo-1|sha-1");

  @Test
  void unconfirmedRootNeverStartsAnotherInvocation() {
    stubReference();
    when(operations.claimOrResume(any(), any(), eq("worker-1"), any()))
        .thenReturn(Optional.empty());

    assertThat(root.claim(request, "worker-1", Duration.ofMinutes(5))).isEmpty();

    ArgumentCaptor<LlmExecutionOperationDescriptor> descriptor =
        ArgumentCaptor.forClass(LlmExecutionOperationDescriptor.class);
    verify(operations).claimOrResume(eq(reference), descriptor.capture(), eq("worker-1"), any());
    assertThat(descriptor.getValue().maxAttempts()).isEqualTo(1);
    verify(operations, never()).beginNewInvocation(any(), any(), any(), any());
  }

  @Test
  void explicitRefreshMayBeginANewOneShotInvocation() {
    stubReference();
    when(operations.claimOrResume(any(), any(), eq("worker-1"), any()))
        .thenReturn(Optional.empty());
    LlmExecutionOperationClaim claim =
        new LlmExecutionOperationClaim(reference, "llmop-explicit", 2, 1, UUID.randomUUID());
    when(operations.beginNewInvocation(any(), any(), eq("worker-1"), any())).thenReturn(claim);

    var active = root.claim(request, "worker-1", Duration.ofMinutes(5), true).orElseThrow();

    assertThat(active.authority()).isNotNull();
    verify(operations).beginNewInvocation(eq(reference), any(), eq("worker-1"), any());
  }

  private void stubReference() {
    when(policyFactory.forProjectEmbedding(
            request.projectId(),
            request.feature(),
            request.sourceType(),
            request.sourceId(),
            request.sourceId(),
            EmbeddingService.PROVIDER,
            request.actorUserId()))
        .thenReturn(reference);
  }
}
