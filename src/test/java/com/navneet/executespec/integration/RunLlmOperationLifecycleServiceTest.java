package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
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
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RunLlmOperationLifecycleServiceTest {

  @Test
  void phaseDescriptorsAreStableAndDoNotSpanThePlanGate() {
    var planning =
        RunLlmOperationLifecycleService.descriptor(
            "run-1", "planning", "RUN_PLANNING", "anthropic|claude|PRODUCTION");
    var resumedExecution =
        RunLlmOperationLifecycleService.descriptor(
            "run-1", "execution", "RUN_EXECUTION", "anthropic|claude|MEDIUM");
    var patch =
        RunLlmOperationLifecycleService.descriptor(
            "run-1", "patch", "RUN_PATCH", "anthropic|claude|PATCH");

    assertThat(planning.operationKey()).isEqualTo("pipeline-run:run-1:planning");
    assertThat(resumedExecution.operationKey()).isEqualTo("pipeline-run:run-1:execution");
    assertThat(patch.operationKey()).isEqualTo("pipeline-run:run-1:patch");
    assertThat(planning.fingerprint())
        .isEqualTo(
            RunLlmOperationLifecycleService.descriptor(
                    "run-1", "planning", "RUN_PLANNING", "anthropic|claude|PRODUCTION")
                .fingerprint())
        .isNotEqualTo(resumedExecution.fingerprint());
    assertThat(planning.maxAttempts()).isEqualTo(4);
    assertThat(resumedExecution.maxAttempts()).isEqualTo(4);
    assertThat(RunLlmOperationLifecycleService.leaseOwnerForTest())
        .startsWith("run-orchestrator:")
        .hasSize(53)
        .matches("run-orchestrator:[0-9a-f-]{36}");
  }

  @Test
  void postRunDescriptorsAreOneShotAndDistinct() {
    var validation =
        RunLlmOperationLifecycleService.oneShotDescriptor(
            "run-1", "validation", "RUN_VALIDATION", "anthropic|claude");
    var postValidation =
        RunLlmOperationLifecycleService.oneShotDescriptor(
            "run-1",
            "post-validation-continuation",
            "RUN_POST_VALIDATION_CONTINUATION",
            "task-1|STEP_UP_MODEL|openai|gpt");
    var testGeneration =
        RunLlmOperationLifecycleService.oneShotDescriptor(
            "run-1",
            "async-test-generation",
            "RUN_ASYNC_TEST_GENERATION",
            "source|workspace|test-planner-v1");

    assertThat(validation.maxAttempts()).isEqualTo(1);
    assertThat(postValidation.maxAttempts()).isEqualTo(1);
    assertThat(testGeneration.maxAttempts()).isEqualTo(1);
    assertThat(validation.operationKey()).isEqualTo("pipeline-run:run-1:validation");
    assertThat(postValidation.operationKey())
        .isEqualTo("pipeline-run:run-1:post-validation-continuation");
    assertThat(testGeneration.operationKey()).isEqualTo("pipeline-run:run-1:async-test-generation");
    assertThat(validation.fingerprint())
        .isNotEqualTo(postValidation.fingerprint())
        .isNotEqualTo(testGeneration.fingerprint());
  }

  @Test
  void claimBindsTheRealCoordinatorAuthorityWithoutMockingOpaqueLease() {
    LlmExecutionOperationService operations = mock(LlmExecutionOperationService.class);
    LlmExecutionOperationCoordinator coordinator = new LlmExecutionOperationCoordinator(operations);
    RunLlmOperationLifecycleService service =
        new RunLlmOperationLifecycleService(
            mock(PipelineRunRepository.class), mock(LlmExecutionPolicyFactory.class), coordinator);
    LlmExecutionReference reference =
        new LlmExecutionReference("ctx-1", LlmExecutionScope.customer("org-1"));
    LlmExecutionOperationClaim claim =
        new LlmExecutionOperationClaim(reference, "operation-1", 1, 1, UUID.randomUUID());
    when(operations.claimOrResume(
            eq(reference),
            any(LlmExecutionOperationDescriptor.class),
            anyString(),
            eq(Duration.ofHours(4))))
        .thenReturn(Optional.of(claim));
    ExecutionContext base =
        new ExecutionContext("run-1", "__root__", Path.of("/tmp").toAbsolutePath(), reference);

    var lease = service.claimPatch(base, "run-1", "anthropic", "claude");
    ExecutionContext bound = service.bind(base, lease);

    assertThat(bound.llmExecutionOperationAuthority()).isNotNull();
    ArgumentCaptor<LlmExecutionOperationDescriptor> descriptor =
        ArgumentCaptor.forClass(LlmExecutionOperationDescriptor.class);
    verify(operations)
        .claimOrResume(eq(reference), descriptor.capture(), anyString(), eq(Duration.ofHours(4)));
    assertThat(descriptor.getValue().operationKey()).isEqualTo("pipeline-run:run-1:patch");
  }

  @Test
  void ambiguousEmptyClaimFailsClosedWithoutStartingANewInvocation() {
    LlmExecutionOperationService operations = mock(LlmExecutionOperationService.class);
    RunLlmOperationLifecycleService service =
        new RunLlmOperationLifecycleService(
            mock(PipelineRunRepository.class),
            mock(LlmExecutionPolicyFactory.class),
            new LlmExecutionOperationCoordinator(operations));
    LlmExecutionReference reference =
        new LlmExecutionReference("ctx-1", LlmExecutionScope.customer("org-1"));
    when(operations.claimOrResume(eq(reference), any(), anyString(), eq(Duration.ofHours(4))))
        .thenReturn(Optional.empty());
    ExecutionContext base =
        new ExecutionContext("run-1", "__root__", Path.of("/tmp").toAbsolutePath(), reference);

    assertThatThrownBy(() -> service.claimExecution(base, "run-1", "openai", "gpt"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("terminal, active elsewhere, or not retryable");
    verify(operations, never()).beginNewInvocation(any(), any(), anyString(), any());
  }

  @Test
  void buildTaskReplacementStartsANewInvocationAndBindsItsAuthority() {
    LlmExecutionOperationService operations = mock(LlmExecutionOperationService.class);
    RunLlmOperationLifecycleService service =
        new RunLlmOperationLifecycleService(
            mock(PipelineRunRepository.class),
            mock(LlmExecutionPolicyFactory.class),
            new LlmExecutionOperationCoordinator(operations));
    LlmExecutionReference reference =
        new LlmExecutionReference("ctx-1", LlmExecutionScope.customer("org-1"));
    LlmExecutionOperationClaim claim =
        new LlmExecutionOperationClaim(reference, "operation-2", 2, 1, UUID.randomUUID());
    when(operations.beginNewInvocation(eq(reference), any(), anyString(), eq(Duration.ofHours(4))))
        .thenReturn(claim);
    ExecutionContext base =
        new ExecutionContext("run-1", "__root__", Path.of("/tmp").toAbsolutePath(), reference);

    var lease = service.beginBuildTaskAttempt(base, "run-1", "T1", 0, "contract|attempt=2");

    assertThat(lease.context().llmExecutionOperationAuthority()).isNotNull();
    verify(operations)
        .beginNewInvocation(eq(reference), any(), anyString(), eq(Duration.ofHours(4)));
    verify(operations, never()).claimOrResume(any(), any(), anyString(), any());
  }
}
