package com.navneet.executespec.executioncontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;

class LlmExecutionOperationCoordinatorTest {

  private static final LlmExecutionReference REFERENCE =
      new LlmExecutionReference("llmctx_root", LlmExecutionScope.platformInternal());
  private static final LlmExecutionOperationClaim CLAIM =
      new LlmExecutionOperationClaim(
          REFERENCE, "llmop_root", 1, 1, UUID.fromString("00000000-0000-0000-0000-000000000001"));

  private final LlmExecutionOperationService operationService =
      mock(LlmExecutionOperationService.class);
  private final LlmExecutionOperationCoordinator coordinator =
      new LlmExecutionOperationCoordinator(operationService);

  @Test
  void claimBindsExactAuthorityAndCompletesOnlyWhenRootRequestsIt() {
    var descriptor =
        LlmExecutionOperationDescriptor.of("run:1:planning:v1", "PLANNING", "immutable-root-v1", 2);
    when(operationService.claimOrResume(REFERENCE, descriptor, "node-a", Duration.ofMinutes(2)))
        .thenReturn(Optional.of(CLAIM));
    when(operationService.complete(CLAIM, "node-a")).thenReturn(true);

    LlmExecutionOperationCoordinator.Lease lease =
        coordinator
            .claimOrResume(REFERENCE, descriptor, "node-a", Duration.ofMinutes(2))
            .orElseThrow();
    ExecutionContext base =
        new ExecutionContext("run-1", "planning", Path.of("/tmp").toAbsolutePath(), REFERENCE);
    ExecutionContext bound = coordinator.bind(base, lease);

    assertThat(bound.llmExecutionOperationAuthority()).isNotNull();
    assertThat(bound.child("planner").llmExecutionOperationAuthority())
        .isSameAs(bound.llmExecutionOperationAuthority());
    verify(operationService).claimOrResume(REFERENCE, descriptor, "node-a", Duration.ofMinutes(2));

    assertThat(coordinator.complete(lease)).isTrue();
    verify(operationService).complete(CLAIM, "node-a");
  }

  @Test
  void refusesToBindLeaseAcrossDurableReferences() {
    var descriptor =
        LlmExecutionOperationDescriptor.of("run:1:planning:v1", "PLANNING", "immutable-root-v1", 2);
    when(operationService.claimOrResume(REFERENCE, descriptor, "node-a", Duration.ofMinutes(2)))
        .thenReturn(Optional.of(CLAIM));
    LlmExecutionOperationCoordinator.Lease lease =
        coordinator
            .claimOrResume(REFERENCE, descriptor, "node-a", Duration.ofMinutes(2))
            .orElseThrow();
    LlmExecutionReference other =
        new LlmExecutionReference("llmctx_other", LlmExecutionScope.platformInternal());
    ExecutionContext context =
        new ExecutionContext("run-1", "planning", Path.of("/tmp").toAbsolutePath(), other);

    assertThatThrownBy(() -> coordinator.bind(context, lease))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("different durable operation reference");
  }

  @Test
  void leaseIsRedactedAndJacksonIgnored() throws Exception {
    var descriptor =
        LlmExecutionOperationDescriptor.of("run:1:planning:v1", "PLANNING", "immutable-root-v1", 2);
    when(operationService.claimOrResume(REFERENCE, descriptor, "node-a", Duration.ofMinutes(2)))
        .thenReturn(Optional.of(CLAIM));
    LlmExecutionOperationCoordinator.Lease lease =
        coordinator
            .claimOrResume(REFERENCE, descriptor, "node-a", Duration.ofMinutes(2))
            .orElseThrow();

    assertThat(lease.toString()).isEqualTo("LlmExecutionOperationCoordinator.Lease[REDACTED]");
    assertThat(new ObjectMapper().writeValueAsString(new Envelope(lease))).isEqualTo("{}");
  }

  private record Envelope(LlmExecutionOperationCoordinator.Lease lease) {}
}
