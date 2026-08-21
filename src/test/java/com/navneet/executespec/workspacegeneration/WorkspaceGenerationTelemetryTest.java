package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.sse.SseBroadcaster;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class WorkspaceGenerationTelemetryTest {

  @Test
  void emitsReplayableStateAndRecordsRouteCommandRecoveryAndControlMetrics() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    SseBroadcaster broadcaster = mock(SseBroadcaster.class);
    WorkspaceGenerationTelemetry telemetry =
        new WorkspaceGenerationTelemetry(new Metrics(registry), broadcaster);
    UUID attemptId = UUID.randomUUID();
    WorkspaceGenerationLifecycle.Prepared prepared =
        new WorkspaceGenerationLifecycle.Prepared(
            attemptId,
            new CorrectionWorkspaceTransaction.Checkpoint(
                java.nio.file.Path.of("/tmp/workspace"),
                List.of(
                    new CorrectionWorkspaceTransaction.RepositoryCheckpoint(
                        java.nio.file.Path.of("/tmp/workspace"), "1".repeat(40), "2".repeat(40)))),
            mock(RunWorkspaceAuthorityService.Lease.class));
    TaskAcceptanceContract contract =
        new TaskAcceptanceContract(
            2,
            "T1",
            0,
            "SERVER_RESOLVED_FAILURE_FIRST_V2",
            "REQUIRED_NON_NOOP",
            "REQUIRED",
            List.of("src/app.py"),
            "PYTHON_LIBRARY",
            "PYTHON",
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            true,
            List.of("."),
            List.of(),
            List.of(),
            "c".repeat(64));

    telemetry.state(
        "run-1", "T1", prepared, "VERIFYING", contract, "openai", "gpt-5.4", "a".repeat(64), null);
    telemetry.verification("PASSED", "PYTHON_LIBRARY", "BROAD_FINAL", Duration.ofMillis(12));
    telemetry.execution("openai", "gpt-5.4", "SUCCESS", 7, 0.42);
    telemetry.recovery("run-1", "T1", attemptId, "ROLLBACK", "SUCCEEDED", "ROLLED_BACK");
    telemetry.control("run-1", "T1", attemptId, "ACCEPTED_FRONTIER_CAS", "CONFLICT", "ACCEPT");
    telemetry.unresolved(3);

    verify(broadcaster)
        .emit(
            eq("run-1"),
            argThat(
                event ->
                    "WORKSPACE_GENERATION".equals(event.type())
                        && "VERIFYING".equals(event.data().get("state"))
                        && attemptId.toString().equals(event.data().get("attemptId"))
                        && "openai".equals(event.data().get("provider"))));
    assertThat(
            registry
                .get("executespec.v2.workspace_generation.verification")
                .tag("scope", "PYTHON_LIBRARY")
                .tag("command_class", "BROAD_FINAL")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("executespec.v2.workspace_generation.attempt")
                .tag("provider", "openai")
                .tag("model", "gpt-5.4")
                .tag("outcome", "SUCCESS")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(registry.get("executespec.v2.workspace_generation.unresolved").gauge().value())
        .isEqualTo(3);
    assertThat(
            registry
                .get("executespec.v2.workspace_generation.control")
                .tag("event", "ACCEPTED_FRONTIER_CAS")
                .tag("outcome", "CONFLICT")
                .counter()
                .count())
        .isEqualTo(1);
  }
}
