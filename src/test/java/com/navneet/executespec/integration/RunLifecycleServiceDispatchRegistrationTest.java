package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** Regression coverage for submit-before-registration recovery races. */
class RunLifecycleServiceDispatchRegistrationTest {

  private final RunControlPort controlPort = mock(RunControlPort.class);
  private final PipelineExecutorPool pool = mock(PipelineExecutorPool.class);
  private final ExecutorService executorService = mock(ExecutorService.class);
  private RunLifecycleService service;

  @BeforeEach
  void setUp() {
    when(pool.executor()).thenReturn(executorService);
    service =
        new RunLifecycleService(
            null,
            null,
            null,
            null,
            null,
            controlPort,
            null,
            null,
            pool,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
  }

  @Test
  void recoveryTaskIsRegisteredBeforeAnInlineExecutorCanRunAndAlwaysCleansUp() {
    Runnable operation = mock(Runnable.class);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              Runnable submitted = invocation.getArgument(0);
              submitted.run();
              return null;
            })
        .when(executorService)
        .execute(any(Runnable.class));

    service.dispatchRegistered("run-1", operation);

    InOrder order = inOrder(controlPort, executorService, operation);
    order.verify(controlPort).putActiveRun(eq("run-1"), any());
    order.verify(executorService).execute(any(Runnable.class));
    order.verify(operation).run();
    order.verify(controlPort).removeActiveRun(eq("run-1"), any());
  }

  @Test
  void executorRejectionRemovesThePreRegisteredRecoveryHandle() {
    org.mockito.Mockito.doThrow(new RejectedExecutionException("stopping"))
        .when(executorService)
        .execute(any(Runnable.class));

    assertThatThrownBy(() -> service.dispatchRegistered("run-2", () -> {}))
        .isInstanceOf(RejectedExecutionException.class)
        .hasMessage("stopping");

    verify(controlPort).putActiveRun(eq("run-2"), any());
    verify(controlPort).removeActiveRun(eq("run-2"), any());
  }

  @Test
  void staleCompletionCannotRemoveANewerSameRunRegistration() {
    Runnable operation = mock(Runnable.class);
    java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>> original =
        new java.util.concurrent.atomic.AtomicReference<>();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              original.set(invocation.getArgument(1));
              return null;
            })
        .when(controlPort)
        .putActiveRun(eq("run-3"), any());
    org.mockito.Mockito.doAnswer(
            invocation -> {
              Runnable submitted = invocation.getArgument(0);
              submitted.run();
              return null;
            })
        .when(executorService)
        .execute(any(Runnable.class));

    service.dispatchRegistered("run-3", operation);

    verify(operation).run();
    verify(controlPort).removeActiveRun("run-3", original.get());
    verify(controlPort, never()).removeActiveRun("run-3");
  }
}
