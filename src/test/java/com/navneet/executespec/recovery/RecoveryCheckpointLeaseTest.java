package com.navneet.executespec.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryCheckpointLeaseTest {

  @TempDir Path workspace;

  @Test
  void firstOwnerAcquiresDurableRunAndPlanBoundLease() throws Exception {
    Instant now = Instant.parse("2026-07-12T10:00:00Z");

    var result = RecoveryCheckpointLease.acquire(workspace, "run-1", "instance-a", "plan-a", now);

    assertThat(result.acquired()).isTrue();
    assertThat(result.reason()).isEqualTo("ACQUIRED");
    String lease = Files.readString(workspace.resolve(".agent-context/recovery-lease.json"));
    assertThat(lease).contains("\"runId\":\"run-1\"");
    assertThat(lease).contains("\"instanceId\":\"instance-a\"");
    assertThat(lease).contains(RecoveryCheckpointLease.sha256("plan-a"));
  }

  @Test
  void freshPeerOwnerIsRefusedEvenWhenDatabaseClaimWasStale() {
    Instant now = Instant.parse("2026-07-12T10:00:00Z");
    assertThat(
            RecoveryCheckpointLease.acquire(workspace, "run-1", "instance-a", "plan-a", now)
                .acquired())
        .isTrue();

    var result =
        RecoveryCheckpointLease.acquire(
            workspace, "run-1", "instance-b", "plan-a", now.plusSeconds(30));

    assertThat(result)
        .isEqualTo(new RecoveryCheckpointLease.AcquireResult(false, "FRESH_CHECKPOINT_OWNER"));
  }

  @Test
  void stalePeerWithSamePlanCanTransferOwnership() {
    Instant original = Instant.parse("2026-07-12T10:00:00Z");
    assertThat(
            RecoveryCheckpointLease.acquire(workspace, "run-1", "instance-a", "plan-a", original)
                .acquired())
        .isTrue();

    var result =
        RecoveryCheckpointLease.acquire(
            workspace,
            "run-1",
            "instance-b",
            "plan-a",
            original.plus(RecoveryCheckpointLease.FRESH_OWNER_WINDOW).plusSeconds(1));

    assertThat(result.acquired()).isTrue();
  }

  @Test
  void incompatiblePlanOrMalformedSidecarFailsClosed() throws Exception {
    Instant now = Instant.parse("2026-07-12T10:00:00Z");
    assertThat(
            RecoveryCheckpointLease.acquire(workspace, "run-1", "instance-a", "plan-a", now)
                .acquired())
        .isTrue();
    assertThat(
            RecoveryCheckpointLease.acquire(
                    workspace, "run-1", "instance-b", "plan-b", now.plus(Duration.ofHours(1)))
                .reason())
        .isEqualTo("INCOMPATIBLE_CHECKPOINT");

    Files.writeString(workspace.resolve(".agent-context/recovery-lease.json"), "not-json");
    assertThat(
            RecoveryCheckpointLease.acquire(
                    workspace, "run-1", "instance-b", "plan-a", now.plus(Duration.ofHours(1)))
                .reason())
        .isEqualTo("MALFORMED_LEASE");
  }
}
