package com.navneet.executespec.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.deployment.WorkspaceRestoreService;
import com.navneet.executespec.sse.SseBroadcaster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** Poison continuation payloads must terminate durably without touching a workspace or worker. */
@ExtendWith(MockitoExtension.class)
class PlanGateResumeDispatcherPoisonTest {

  @Mock PlanGateResumeStore store;
  @Mock WorkspaceRestoreService workspaceRestoreService;
  @Mock PipelineExecutorPool pool;
  @Mock RunControlPort controlPort;
  @Mock SseBroadcaster broadcaster;
  @TempDir Path workspace;

  private PlanGateResumeDispatcher dispatcher;

  @AfterEach
  void shutdownExecutors() {
    if (dispatcher != null) dispatcher.shutdownLeaseRenewalExecutor();
  }

  @Test
  void digestMismatchIsDeadLetteredWithoutWorkspaceOrWorkerDispatch() {
    PlanGateResumeStore.Lease lease = lease(1, "{\"schemaVersion\":1}", "0".repeat(64));
    arrangeSingleClaim(lease);

    dispatcher.kick();

    verify(store).dead(eq(lease), eq("PLAN_GATE_CONTINUATION_DIGEST_MISMATCH"), any());
    verify(workspaceRestoreService, never()).ensureWorkspaceAvailable(anyString());
    verify(controlPort, never()).putExternallyHeartbeatedActiveRun(any(), any());
  }

  @Test
  void unsupportedHandlerSchemaIsDeadLetteredWithoutParsingOrDispatch() {
    String payload = "{\"schemaVersion\":2}";
    PlanGateResumeStore.Lease lease = lease(2, payload, PlanGateOpenService.sha256(payload));
    arrangeSingleClaim(lease);

    dispatcher.kick();

    verify(store).dead(eq(lease), contains("UNSUPPORTED_PLAN_GATE_CONTINUATION_SCHEMA:2"), any());
    verify(workspaceRestoreService, never()).ensureWorkspaceAvailable(anyString());
    verify(controlPort, never()).putExternallyHeartbeatedActiveRun(any(), any());
  }

  @Test
  void payloadSchemaMismatchIsDeadLetteredWithoutDispatch() {
    String payload =
        "{\"schemaVersion\":2,\"localRun\":false,"
            + "\"requireContinuationConfirmation\":false,"
            + "\"requireContinuationBenchmarkPrior\":false,\"pinnedModel\":null,"
            + "\"lowComplexityWorkerStartTierOverride\":null}";
    PlanGateResumeStore.Lease lease = lease(1, payload, PlanGateOpenService.sha256(payload));
    arrangeSingleClaim(lease);

    dispatcher.kick();

    verify(store).dead(eq(lease), eq("PLAN_GATE_CONTINUATION_SCHEMA_MISMATCH"), any());
    verify(controlPort, never()).putExternallyHeartbeatedActiveRun(any(), any());
  }

  @Test
  void durablePreflightFailureIsDeadLetteredBeforeWorkspaceRestore() {
    String payload =
        "{\"schemaVersion\":1,\"localRun\":false,"
            + "\"requireContinuationConfirmation\":false,"
            + "\"requireContinuationBenchmarkPrior\":false,\"pinnedModel\":null,"
            + "\"lowComplexityWorkerStartTierOverride\":null}";
    PlanGateResumeStore.Lease lease = lease(1, payload, PlanGateOpenService.sha256(payload));
    arrangeSingleClaim(lease);
    when(store.heartbeat(eq(lease), any(), any())).thenReturn(true);
    doThrow(new PlanGateResumeStore.PoisonMessageException("PLAN_GATE_RECEIPT_CROSS_ROW_MISMATCH"))
        .when(store)
        .preflight(eq(lease), any());

    dispatcher.kick();

    verify(store).dead(eq(lease), eq("PLAN_GATE_RECEIPT_CROSS_ROW_MISMATCH"), any());
    verify(workspaceRestoreService, never()).ensureWorkspaceAvailable(anyString());
    verify(store, never()).handoff(any(), anyString(), any());
    verify(controlPort, never()).putExternallyHeartbeatedActiveRun(any(), any());
  }

  private void arrangeSingleClaim(PlanGateResumeStore.Lease lease) {
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(workspace.toString());
    dispatcher =
        new PlanGateResumeDispatcher(
            store,
            new ObjectMapper(),
            new PlanGateWorkspaceRecovery(store, properties, workspaceRestoreService),
            pool,
            controlPort,
            broadcaster);
    ReflectionTestUtils.setField(dispatcher, "leaseSeconds", 120L);
    ReflectionTestUtils.setField(dispatcher, "maxBatch", 2);
    when(store.exhaustedClaimableRunIds(any())).thenReturn(List.of());
    when(store.claimNext(any(), any(), any())).thenReturn(Optional.of(lease), Optional.empty());
  }

  private static PlanGateResumeStore.Lease lease(
      int handlerVersion, String payload, String digest) {
    return new PlanGateResumeStore.Lease(
        "poison-run",
        "gate:plan:poison-run",
        payload,
        digest,
        handlerVersion,
        1,
        3,
        "node-a",
        UUID.randomUUID());
  }
}
