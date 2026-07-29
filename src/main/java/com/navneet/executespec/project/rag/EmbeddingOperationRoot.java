package com.navneet.executespec.project.rag;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import com.navneet.executespec.executioncontext.LlmExecutionOperationAuthority;
import com.navneet.executespec.executioncontext.LlmExecutionOperationCoordinator;
import com.navneet.executespec.executioncontext.LlmExecutionOperationDescriptor;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Issues one-shot logical-operation authority for durable document/code embedding roots. */
@Service
@RequiredArgsConstructor
class EmbeddingOperationRoot {

  record Request(
      String projectId,
      String feature,
      String sourceType,
      String sourceId,
      String actorUserId,
      String operationKey,
      String operationKind,
      String immutableIdentity) {}

  record Active(
      LlmExecutionReference reference,
      LlmExecutionOperationCoordinator.Lease lease,
      LlmExecutionOperationAuthority authority) {}

  private final LlmExecutionPolicyFactory policyFactory;
  private final LlmExecutionOperationCoordinator coordinator;

  Optional<Active> claim(Request request, String owner, Duration leaseDuration) {
    return claim(request, owner, leaseDuration, false);
  }

  Optional<Active> claim(
      Request request, String owner, Duration leaseDuration, boolean confirmedNewInvocation) {
    LlmExecutionReference reference =
        policyFactory.forProjectEmbedding(
            request.projectId(),
            request.feature(),
            request.sourceType(),
            request.sourceId(),
            request.sourceId(),
            EmbeddingService.PROVIDER,
            request.actorUserId());
    LlmExecutionOperationDescriptor descriptor =
        LlmExecutionOperationDescriptor.of(
            request.operationKey(), request.operationKind(), request.immutableIdentity(), 1);
    Optional<LlmExecutionOperationCoordinator.Lease> claimed =
        coordinator.claimOrResume(reference, descriptor, owner, leaseDuration);
    if (claimed.isEmpty() && confirmedNewInvocation) {
      claimed =
          Optional.of(coordinator.beginNewInvocation(reference, descriptor, owner, leaseDuration));
    }
    return claimed.map(lease -> active(request, reference, lease));
  }

  private Active active(
      Request request,
      LlmExecutionReference reference,
      LlmExecutionOperationCoordinator.Lease lease) {
    ExecutionContext bound =
        coordinator.bind(
            new ExecutionContext(
                request.operationKey(),
                request.operationKind().toLowerCase(java.util.Locale.ROOT),
                Path.of(System.getProperty("java.io.tmpdir", "/tmp")).toAbsolutePath().normalize(),
                reference),
            lease);
    return new Active(reference, lease, bound.llmExecutionOperationAuthority());
  }

  boolean heartbeat(Active active, Duration leaseDuration) {
    return coordinator.heartbeat(active.lease(), leaseDuration);
  }

  void complete(Active active) {
    if (!coordinator.complete(active.lease())) {
      throw new IllegalStateException("Embedding logical operation could not be completed");
    }
  }

  void terminate(Active active, String code, String message) {
    if (active == null) return;
    if (coordinator.acknowledgeCancellation(active.lease())) return;
    if (!coordinator.fail(active.lease(), code, message, false, null)) {
      throw new IllegalStateException("Embedding logical operation could not be terminalized");
    }
  }

  void requestCancellation(Request request) {
    LlmExecutionReference reference =
        policyFactory.forProjectEmbedding(
            request.projectId(),
            request.feature(),
            request.sourceType(),
            request.sourceId(),
            request.sourceId(),
            EmbeddingService.PROVIDER,
            request.actorUserId());
    coordinator.requestCancellation(reference, request.operationKey());
  }
}
