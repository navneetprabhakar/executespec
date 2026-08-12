package com.navneet.executespec.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL plus a real Docker runtime proof for the command crash windows V389/V391 must close,
 * running against the exact current V393 schema.
 *
 * <p>This is deliberately not a mock-runtime test. It provisions hardened containers, kills a live
 * {@code docker exec} client while its in-container process is running, expires the database lease,
 * and performs recovery through a second store/dispatcher identity. The shared BUILD flag is never
 * read or enabled.
 */
@Tag("integration")
@Testcontainers
class SandboxCommandRealContainerRecoveryPostgresTest {

  private static final String IMAGE = "executespec/sandbox-toolchain:litmus-20260802";
  private static final Duration LIVE_LEASE = Duration.ofMinutes(5);
  private static final String CANDIDATE = "a".repeat(64);
  private static final String CONTRACT = "b".repeat(64);
  private static final String BEFORE = "c".repeat(64);
  private static final String AFTER = "d".repeat(64);

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("sandbox_command_real_runtime")
          .withUsername("test")
          .withPassword("test");

  @TempDir Path temporaryRoot;

  private final List<String> containerNames = new ArrayList<>();

  @AfterEach
  void removeAnyContainerLeftByAFailedAssertion() throws Exception {
    for (String name : containerNames) {
      new ProcessBuilder("docker", "rm", "--force", "--volumes", name)
          .redirectErrorStream(true)
          .start()
          .waitFor(20, TimeUnit.SECONDS);
    }
  }

  @Test
  void crashWindowsConvergeWithoutLateSuccessAndReplacementOwnerDestroysUnknownRuntime()
      throws Exception {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success AND version='393'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE indexname IN (?,?,?)",
                Integer.class,
                "uq_workspace_publication_attempt_sequence",
                "uq_workspace_publication_provision_sequence",
                "uq_workspace_publication_provision_active_repo"))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT flag_value FROM feature_flag WHERE flag_key=?",
                String.class,
                "build.shared-code-change-loop.enabled"))
        .isEqualTo("false");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE indexname=?",
                Integer.class,
                "idx_sandbox_command_unsettled_recovery"))
        .isEqualTo(1);

    SandboxProperties properties = new SandboxProperties();
    properties.setWorkspaceRoot(temporaryRoot.resolve("sandboxes").toString());
    properties.setImage(IMAGE);
    properties.setCpus(0.5);
    properties.setMemoryBytes(128L * 1024 * 1024);
    properties.setPids(32);
    properties.setTmpfsBytes(8L * 1024 * 1024);
    properties.setWallClock(Duration.ofMinutes(3));
    properties.setCommandTimeoutSeconds(30);
    properties.setLeaseDuration(Duration.ofSeconds(2));
    Files.createDirectories(Path.of(properties.getWorkspaceRoot()));

    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled(anyString())).thenReturn(true);
    ContainerStrictV1Runtime runtimeA =
        new ContainerStrictV1Runtime(properties, flags, temporaryRoot.resolve("agent-workspaces"));
    assertThat(runtimeA.available()).as("a real Docker engine is required for this gate").isTrue();

    RunSandboxStore storeA = new RunSandboxStore(jdbc);
    RunSandboxRuntimeDispatcher dispatcherA =
        new RunSandboxRuntimeDispatcher(storeA, flags, List.of(runtimeA));
    SandboxCommandJournalStore journal = new SandboxCommandJournalStore(jdbc);

    // Kill window 1: durable ADMITTED, no external dispatch. Recovery can state exactly that no
    // command was sent, and emits UNAVAILABLE rather than leaving an immortal intent.
    Fixture beforeDispatch =
        provision(jdbc, storeA, dispatcherA, runtimeA, properties, "before-dispatch");
    SandboxCommandJournalStore.Admission admitted =
        journal.admit(
            request(beforeDispatch, "before-dispatch"),
            beforeDispatch.sandbox(),
            beforeDispatch.lease(),
            1);
    expireLease(jdbc, beforeDispatch.sandbox().id());
    runTakeoverProbe(
        properties, beforeDispatch.sandbox().id(), admitted.id(), "UNAVAILABLE:FAILED");
    assertThat(commandState(jdbc, admitted.id())).isEqualTo("UNAVAILABLE");
    assertThat(receiptOutcome(jdbc, admitted.id())).isEqualTo("UNAVAILABLE");
    assertThat(storeA.get(beforeDispatch.sandbox().id()).orElseThrow().state())
        .isEqualTo(SandboxState.FAILED);

    // Kill window 2: the command is physically running. Kill the host-side docker exec client,
    // expire the owner lease, and prove neither its late completion nor a replacement JVM can turn
    // the durable DISPATCHED row green. Replacement recovery destroys the still-live container.
    Fixture duringDispatch =
        provision(jdbc, storeA, dispatcherA, runtimeA, properties, "during-dispatch");
    SandboxCommandJournalStore.Admission running =
        journal.admit(
            request(duringDispatch, "during-dispatch"),
            duringDispatch.sandbox(),
            duringDispatch.lease(),
            1);
    journal.markDispatched(running, duringDispatch.lease());
    Path started = duringDispatch.workspace().resolve("command-started");
    Path finished = duringDispatch.workspace().resolve("command-finished");
    Process dockerExec =
        new ProcessBuilder(
                SandboxContainerCommand.exec(
                    duringDispatch.sandbox().runtimeHandle(),
                    new SandboxExecRequest(
                        List.of(
                            "sh", "-c", "touch command-started; sleep 60; touch command-finished"),
                        "/workspace",
                        Duration.ofMinutes(2))))
            .redirectErrorStream(true)
            .start();
    awaitExists(started, Duration.ofSeconds(15));
    dockerExec.destroyForcibly();
    dockerExec.waitFor(10, TimeUnit.SECONDS);
    assertThat(finished).doesNotExist();
    expireLease(jdbc, duringDispatch.sandbox().id());
    assertLateSuccessRejected(transaction, journal, running, duringDispatch.lease());
    runTakeoverProbe(
        properties, duringDispatch.sandbox().id(), running.id(), "INDETERMINATE:FAILED");
    assertThat(commandState(jdbc, running.id())).isEqualTo("INDETERMINATE");
    assertThat(runtimeA.alive(storeA.get(duringDispatch.sandbox().id()).orElseThrow())).isFalse();
    assertThat(finished).doesNotExist();

    // Kill window 3: the physical process exited successfully but the JVM died before settlement.
    // Process output is not a durable receipt; after lease loss it remains INDETERMINATE.
    Fixture afterExit = provision(jdbc, storeA, dispatcherA, runtimeA, properties, "after-exit");
    SandboxCommandJournalStore.Admission exited =
        journal.admit(request(afterExit, "after-exit"), afterExit.sandbox(), afterExit.lease(), 1);
    journal.markDispatched(exited, afterExit.lease());
    SandboxExecResult physicalResult =
        dispatcherA.exec(
            afterExit.sandbox(),
            afterExit.lease(),
            new SandboxExecRequest(
                List.of("sh", "-c", "printf complete > command-complete"),
                "/workspace",
                Duration.ofSeconds(20)));
    assertThat(physicalResult.succeeded()).isTrue();
    assertThat(afterExit.workspace().resolve("command-complete")).hasContent("complete");
    expireLease(jdbc, afterExit.sandbox().id());
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        journal.settle(
                            exited,
                            afterExit.lease(),
                            SandboxCommandJournalStore.TerminalOutcome.SUCCEEDED,
                            physicalResult,
                            AFTER,
                            null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SANDBOX_COMMAND_SETTLEMENT_FENCE_REJECTED");
    runTakeoverProbe(properties, afterExit.sandbox().id(), exited.id(), "INDETERMINATE:FAILED");
    assertThat(commandState(jdbc, exited.id())).isEqualTo("INDETERMINATE");
    assertThat(runtimeA.alive(storeA.get(afterExit.sandbox().id()).orElseThrow())).isFalse();

    // Kill window 4: durable RELEASING intent exists but the owner dies before docker destroy.
    // A replacement identity takes the expired lease, verifies the container fence, destroys it,
    // and publishes the original terminal target.
    Fixture teardown = provision(jdbc, storeA, dispatcherA, runtimeA, properties, "teardown");
    storeA.beginRelease(teardown.lease(), SandboxState.TERMINATED, null, null);
    expireLease(jdbc, teardown.sandbox().id());
    runTakeoverProbe(properties, teardown.sandbox().id(), null, "TERMINATED");
    RunSandbox terminal = storeA.get(teardown.sandbox().id()).orElseThrow();
    assertThat(terminal.state()).isEqualTo(SandboxState.TERMINATED);
    assertThat(runtimeA.alive(terminal)).isFalse();

    // Clear prior terminal cleanup rows, then prove a janitor killed after claim is reclaimed by a
    // replacement janitor using the incremented durable claim attempt.
    VerificationSandboxWorkspaceCleanupStore cleanupStoreA =
        new VerificationSandboxWorkspaceCleanupStore(jdbc);
    VerificationSandboxWorkspaceJanitor janitorA =
        new VerificationSandboxWorkspaceJanitor(cleanupStoreA, properties);
    assertThat(janitorA.sweep(100)).isGreaterThanOrEqualTo(4);

    Fixture janitorFixture = provision(jdbc, storeA, dispatcherA, runtimeA, properties, "janitor");
    Files.writeString(janitorFixture.workspace().resolve("cleanup-proof"), "delete-me");
    dispatcherA.release(janitorFixture.sandbox(), janitorFixture.lease(), SandboxState.TERMINATED);
    VerificationSandboxWorkspaceCleanupStore.Claim abandonedClaim =
        cleanupStoreA.claimNext(Duration.ofMinutes(10)).orElseThrow();
    assertThat(abandonedClaim.sandboxId()).isEqualTo(janitorFixture.sandbox().id());
    jdbc.update(
        "UPDATE run_sandbox_workspace_cleanup SET claimed_at=NOW()-INTERVAL '11 minutes'"
            + " WHERE sandbox_id=?",
        abandonedClaim.sandboxId());
    runJanitorProbe(properties, janitorFixture.sandbox().id());
    assertThat(janitorFixture.workspace()).doesNotExist();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM run_sandbox_workspace_cleanup WHERE sandbox_id=?",
                String.class,
                janitorFixture.sandbox().id()))
        .isEqualTo("COMPLETED");
    assertThat(
            jdbc.queryForObject(
                "SELECT attempts FROM run_sandbox_workspace_cleanup WHERE sandbox_id=?",
                Integer.class,
                janitorFixture.sandbox().id()))
        .isEqualTo(2);
  }

  private Fixture provision(
      JdbcTemplate jdbc,
      RunSandboxStore store,
      RunSandboxRuntimeDispatcher dispatcher,
      ContainerStrictV1Runtime runtime,
      SandboxProperties properties,
      String label)
      throws Exception {
    String runId = "real-container-" + label + "-" + UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    UUID sandboxId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO pipeline_run(run_id,status,run_mode,build_protocol_version)"
            + " VALUES (?,'WORKING','BUILD','SHARED_KERNEL_V2')",
        runId);
    jdbc.update(
        "INSERT INTO workspace_generation_attempt(id,run_id,task_id,task_index,attempt_number,"
            + "attempt_kind,state,workspace_root,authority_digest,task_contract_digest,"
            + "build_protocol_version,verification_command_protocol_version) VALUES "
            + "(?,?,'T1',0,1,'BUILD_TASK','ADMITTED','/tmp/qualification',repeat('e',64),?,"
            + "'SHARED_KERNEL_V2',2)",
        attemptId,
        runId,
        CONTRACT);
    Path workspace =
        Path.of(properties.getWorkspaceRoot()).resolve(sandboxId.toString()).resolve("workspace");
    Files.createDirectories(workspace);
    Files.setPosixFilePermissions(workspace, EnumSet.allOf(PosixFilePermission.class));
    SandboxRuntimeSpec spec =
        runtime.defaultSpec(
            List.of(new SandboxRuntimeSpec.WritableMount(workspace.toString(), "/workspace")));
    RunSandbox created =
        store.createOrGet(
            new RunSandbox.CreateRequest(
                sandboxId,
                runId,
                null,
                null,
                "qualification-user",
                "qualification-org",
                CANDIDATE,
                "[]",
                SandboxPurpose.VERIFICATION,
                SandboxIsolationProfile.CONTAINER_STRICT_V1,
                spec.configDigest(),
                SandboxNetworkPolicy.DENY_ALL,
                SandboxSecretPolicy.NO_HOST_SECRETS,
                Instant.now().plus(Duration.ofMinutes(10)),
                "real-container:" + label + ":" + sandboxId));
    RunSandbox.Lease lease =
        store.tryAcquire(sandboxId, "jvm-a:" + label, LIVE_LEASE).orElseThrow();
    RunSandbox claimed = store.get(created.id()).orElseThrow();
    assertThat(claimed.runtimeEpoch()).isEqualTo(1);
    assertThat(claimed.leaseToken()).isEqualTo(lease.fencingToken());
    RunSandbox ready = dispatcher.provision(claimed, lease, spec);
    containerNames.add(ready.runtimeHandle());
    awaitAlive(runtime, ready, Duration.ofSeconds(10));
    return new Fixture(runId, attemptId, ready, lease, workspace);
  }

  private static SandboxCommandJournalStore.AdmissionRequest request(
      Fixture fixture, String idempotencySuffix) {
    return new SandboxCommandJournalStore.AdmissionRequest(
        fixture.runId(),
        fixture.attemptId(),
        CANDIDATE,
        SandboxCommandJournalStore.mountDigest(
            fixture.workspace().toAbsolutePath().normalize().toString(), "/workspace"),
        "REAL_CONTAINER_QUALIFICATION",
        SandboxCommandJournalStore.argvDigest(List.of("sh", "-c", idempotencySuffix)),
        Duration.ofMinutes(2),
        "real-command:" + idempotencySuffix + ":" + fixture.sandbox().id(),
        BEFORE);
  }

  private static void assertLateSuccessRejected(
      TransactionTemplate transaction,
      SandboxCommandJournalStore journal,
      SandboxCommandJournalStore.Admission admission,
      RunSandbox.Lease staleLease) {
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        journal.settle(
                            admission,
                            staleLease,
                            SandboxCommandJournalStore.TerminalOutcome.SUCCEEDED,
                            SandboxExecResult.completed(0, "late", "", false, Duration.ofMillis(1)),
                            AFTER,
                            null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SANDBOX_COMMAND_SETTLEMENT_FENCE_REJECTED");
  }

  private static void expireLease(JdbcTemplate jdbc, UUID sandboxId) {
    assertThat(
            jdbc.update(
                "UPDATE run_sandbox SET lease_expires_at=NOW()-INTERVAL '1 second' WHERE id=?",
                sandboxId))
        .isEqualTo(1);
  }

  private static String commandState(JdbcTemplate jdbc, UUID commandId) {
    return jdbc.queryForObject(
        "SELECT state FROM sandbox_command_execution WHERE id=?", String.class, commandId);
  }

  private static String receiptOutcome(JdbcTemplate jdbc, UUID commandId) {
    return jdbc.queryForObject(
        "SELECT outcome FROM sandbox_command_receipt WHERE command_id=?", String.class, commandId);
  }

  private static void awaitExists(Path path, Duration timeout) throws Exception {
    Instant deadline = Instant.now().plus(timeout);
    while (!Files.exists(path) && Instant.now().isBefore(deadline)) {
      Thread.sleep(25);
    }
    assertThat(path).as("container command start marker").exists();
  }

  private static void awaitAlive(
      ContainerStrictV1Runtime runtime, RunSandbox sandbox, Duration timeout) throws Exception {
    Instant deadline = Instant.now().plus(timeout);
    while (!runtime.alive(sandbox) && Instant.now().isBefore(deadline)) {
      Thread.sleep(25);
    }
    assertThat(runtime.alive(sandbox)).as("strict container is live and correctly fenced").isTrue();
  }

  private void runTakeoverProbe(
      SandboxProperties properties, UUID sandboxId, UUID commandId, String expected)
      throws Exception {
    runProbe(
        "RECOVER", properties, sandboxId, commandId == null ? "-" : commandId.toString(), expected);
  }

  private void runJanitorProbe(SandboxProperties properties, UUID sandboxId) throws Exception {
    runProbe("JANITOR", properties, sandboxId, "-", "COMPLETED");
  }

  private void runProbe(
      String mode, SandboxProperties properties, UUID sandboxId, String commandId, String expected)
      throws Exception {
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                classpath,
                SandboxRecoveryTakeoverProbe.class.getName(),
                mode,
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                properties.getWorkspaceRoot(),
                temporaryRoot.resolve("agent-workspaces").toString(),
                sandboxId.toString(),
                commandId,
                expected)
            .redirectErrorStream(true)
            .start();
    boolean finished = process.waitFor(30, TimeUnit.SECONDS);
    if (!finished) process.destroyForcibly();
    String output = new String(process.getInputStream().readAllBytes());
    assertThat(finished).as("replacement JVM finished: %s", output).isTrue();
    assertThat(process.exitValue()).as("replacement JVM output: %s", output).isZero();
    assertThat(output).contains("TAKEOVER_PROBE_PASSED");
  }

  private record Fixture(
      String runId, UUID attemptId, RunSandbox sandbox, RunSandbox.Lease lease, Path workspace) {}
}
