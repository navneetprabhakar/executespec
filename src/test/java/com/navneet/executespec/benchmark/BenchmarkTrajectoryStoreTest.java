package com.navneet.executespec.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.security.GeneratedSecretScanner;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkTrajectoryStoreTest {

  @TempDir Path temp;

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void appendsEveryEventRedactedAndSurvivesStoreRestart() throws Exception {
    BenchmarkTrajectoryStore first = store(1024 * 1024);
    first.append(
        "run-1",
        new RunEvent(
            "LOG",
            "run-1",
            "task-1",
            "Bearer abc.def.ghi sk-abcdefghijklmnopqrstuvwxyz0123456789",
            Instant.now(),
            1,
            Map.of(
                "apiKey", "sk-ant-abcdefghijklmnopqrstuvwxyz0123456789",
                "path", "/Users/operator/Desktop/key_rotation.txt",
                "embeddedPayload", "{\"password\":\"raw-password-value\",\"ok\":true}")));
    first.append("run-1", RunEvent.taskStatus("run-1", "task-1", "COMPLETE"));

    // A new component instance represents a backend restart; no in-memory index is required.
    BenchmarkTrajectoryStore restarted = store(1024 * 1024);
    BenchmarkTrajectoryStore.ExportSnapshot snapshot = restarted.prepareExport("run-1");
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    restarted.copyTo(snapshot, output);
    String artifact = output.toString(java.nio.charset.StandardCharsets.UTF_8);

    assertThat(snapshot.eventCount()).isEqualTo(2);
    assertThat(snapshot.truncated()).isFalse();
    assertThat(snapshot.sha256()).hasSize(64);
    assertThat(artifact).contains("[REDACTED]").contains("[REDACTED_PATH]");
    assertThat(artifact)
        .doesNotContain("abc.def.ghi")
        .doesNotContain("sk-ant-")
        .doesNotContain("raw-password-value")
        .doesNotContain("key_rotation.txt");
  }

  @Test
  void crossingSizeGuardMakesExportFailClosedInsteadOfReturningPartialEvidence() {
    BenchmarkTrajectoryStore service = store(80);
    service.append("run-2", RunEvent.log("run-2", null, "x".repeat(500)));

    assertThatThrownBy(() -> service.prepareExport("run-2"))
        .isInstanceOf(BenchmarkTrajectoryStore.IncompleteTrajectoryException.class)
        .hasMessageContaining("truncated");
  }

  @Test
  void retainsMoreThanCustomerReplayCapWithoutTruncation() {
    BenchmarkTrajectoryStore service = store(8 * 1024 * 1024);
    for (int i = 0; i < 650; i++) {
      service.append("run-many", RunEvent.log("run-many", "task-1", "event-" + i));
    }

    BenchmarkTrajectoryStore.ExportSnapshot snapshot = service.prepareExport("run-many");

    assertThat(snapshot.eventCount()).isEqualTo(650);
    assertThat(snapshot.truncated()).isFalse();
  }

  @Test
  void appendStorageFailureCannotBecomeSubmissionReady() throws Exception {
    Path invalidRoot = temp.resolve("not-a-directory");
    java.nio.file.Files.writeString(invalidRoot, "occupied");
    BenchmarkTrajectoryStore service =
        new BenchmarkTrajectoryStore(
            mapper, new GeneratedSecretScanner(), null, invalidRoot.toString(), 1024, 24);
    service.registerBenchmarkRun("run-broken");

    service.append("run-broken", RunEvent.log("run-broken", null, "event"));

    assertThatThrownBy(() -> service.prepareExport("run-broken"))
        .isInstanceOf(BenchmarkTrajectoryStore.MissingTrajectoryException.class);
  }

  @Test
  void exportRescansHistoricalArtifactAndRejectsTamperedSecretOrMalformedNdjson() throws Exception {
    BenchmarkTrajectoryStore service = store(1024 * 1024);
    service.append("run-tampered", RunEvent.log("run-tampered", null, "safe"));
    Path artifact = temp.resolve("run-tampered.events.ndjson");
    java.nio.file.Files.writeString(
        artifact,
        "{\"type\":\"LOG\",\"runId\":\"run-tampered\",\"payload\":\"Bearer abc.def.ghi\"}\n",
        java.nio.file.StandardOpenOption.APPEND);

    assertThatThrownBy(() -> service.prepareExport("run-tampered"))
        .isInstanceOf(BenchmarkTrajectoryStore.IncompleteTrajectoryException.class)
        .hasMessageContaining("unavailable")
        .hasRootCauseMessage("unsafe value found in durable trajectory");

    service.append("run-malformed", RunEvent.log("run-malformed", null, "safe"));
    java.nio.file.Files.writeString(
        temp.resolve("run-malformed.events.ndjson"),
        "not-json\n",
        java.nio.file.StandardOpenOption.APPEND);
    assertThatThrownBy(() -> service.prepareExport("run-malformed"))
        .isInstanceOf(BenchmarkTrajectoryStore.IncompleteTrajectoryException.class);
  }

  @Test
  void copyRevalidatesSnapshotImmediatelyBeforeEgress() throws Exception {
    BenchmarkTrajectoryStore service = store(1024 * 1024);
    service.append("run-1", RunEvent.log("run-1", null, "safe-before-prepare"));
    BenchmarkTrajectoryStore.ExportSnapshot snapshot = service.prepareExport("run-1");
    String unsafe = "{\"type\":\"LOG\",\"runId\":\"run-1\",\"payload\":\"Bearer abc.def.ghi\"}\n";
    java.nio.file.Files.writeString(
        temp.resolve("run-1.events.ndjson"),
        unsafe + " ".repeat((int) Math.max(0, snapshot.sizeBytes() - unsafe.length())),
        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

    assertThatThrownBy(() -> service.copyTo(snapshot, new java.io.ByteArrayOutputStream()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("unsafe value");
  }

  @Test
  void dottedRunIdRoundTripsThroughSnapshotWithoutFilenameReconstruction() throws Exception {
    BenchmarkTrajectoryStore service = store(1024 * 1024);
    String runId = "suite.events.ndjson.case-1";
    service.registerBenchmarkRun(runId);
    service.append(runId, RunEvent.log(runId, null, "safe"));
    BenchmarkTrajectoryStore.ExportSnapshot snapshot = service.prepareExport(runId);
    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();

    service.copyTo(snapshot, output);

    assertThat(snapshot.runId()).isEqualTo(runId);
    assertThat(output.toString(java.nio.charset.StandardCharsets.UTF_8)).contains(runId);
  }

  @Test
  void rejectsPathTraversalRunIds() {
    BenchmarkTrajectoryStore service = store(1024);
    assertThatThrownBy(() -> service.append("../escape", RunEvent.log("x", null, "line")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void normalProductRunIsNotPersistedAndEligibilityIsCached() {
    PipelineRunRepository repository = org.mockito.Mockito.mock(PipelineRunRepository.class);
    org.mockito.Mockito.when(repository.findById("run-product"))
        .thenReturn(
            java.util.Optional.of(
                PipelineRun.builder().runId("run-product").sourceType("MANUAL").build()));
    BenchmarkTrajectoryStore service = store(1024 * 1024, repository, false);

    service.append("run-product", RunEvent.log("run-product", null, "ordinary customer event"));
    service.append("run-product", RunEvent.log("run-product", null, "second event"));

    assertThat(temp.resolve("run-product.events.ndjson")).doesNotExist();
    org.mockito.Mockito.verify(repository).findById("run-product");
  }

  @Test
  void repositoryFallbackRestoresBenchmarkEligibilityAfterRestart() {
    PipelineRunRepository repository = org.mockito.Mockito.mock(PipelineRunRepository.class);
    org.mockito.Mockito.when(repository.findById("run-restarted"))
        .thenReturn(
            java.util.Optional.of(
                PipelineRun.builder().runId("run-restarted").sourceType("benchmark").build()));
    BenchmarkTrajectoryStore service = store(1024 * 1024, repository, false);

    service.append("run-restarted", RunEvent.log("run-restarted", null, "event"));

    assertThat(service.prepareExport("run-restarted").eventCount()).isEqualTo(1);
    org.mockito.Mockito.verify(repository).findById("run-restarted");
  }

  private BenchmarkTrajectoryStore store(long maxBytes) {
    return store(maxBytes, null, true);
  }

  private BenchmarkTrajectoryStore store(
      long maxBytes, PipelineRunRepository repository, boolean explicitlyRegister) {
    BenchmarkTrajectoryStore service =
        new BenchmarkTrajectoryStore(
            mapper, new GeneratedSecretScanner(), repository, temp.toString(), maxBytes, 24);
    if (explicitlyRegister) {
      // Unit fixtures stand in for the benchmark services that register immediately after save.
      for (String runId :
          java.util.List.of(
              "run-1", "run-2", "run-many", "run-broken", "run-tampered", "run-malformed")) {
        service.registerBenchmarkRun(runId);
      }
    }
    return service;
  }
}
