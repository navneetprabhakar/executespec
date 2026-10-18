package com.navneet.executespec.sandboxroute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.sandbox.RunSandboxLifecycleService;
import com.navneet.executespec.sandbox.RunSandboxRuntimeDispatcher;
import com.navneet.executespec.sandbox.RunSandboxStore;
import com.navneet.executespec.sandbox.SandboxCommandJournalStore;
import com.navneet.executespec.sandbox.SandboxIsolationProfile;
import com.navneet.executespec.sandbox.SandboxProperties;
import com.navneet.executespec.sandbox.SandboxState;
import com.navneet.executespec.sandbox.VerificationSandboxWorkspaceJanitor;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationDispatchAuthority;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** A real DB lease protects the read/model phase and cannot resurrect after a takeover. */
@Tag("integration")
@Testcontainers
class VerificationSessionLeasePostgresTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("verification_session_lease")
          .withUsername("test")
          .withPassword("test");

  @TempDir Path root;

  @Test
  void renewalProtectsIdleReviewAndTakeoverPermanentlyFencesTheOldSession() {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    jdbc.update(
        "INSERT INTO pipeline_run(run_id,status,run_mode) VALUES"
            + " ('lease-review','WORKING','BUILD')");
    var store = new RunSandboxStore(jdbc);
    var flags = mock(FeatureFlagService.class);
    when(flags.isEnabled(anyString())).thenReturn(true);
    var properties = new SandboxProperties();
    properties.setWorkspaceRoot(root.toString());
    properties.setImage("registry.example/toolchain@sha256:" + "a".repeat(64));
    var dispatcher = mock(RunSandboxRuntimeDispatcher.class);
    when(dispatcher.selectedProfile()).thenReturn(SandboxIsolationProfile.CONTAINER_STRICT_V1);
    var lifecycle =
        new RunSandboxLifecycleService(
            store, dispatcher, flags, properties, mock(VerificationSandboxWorkspaceJanitor.class));
    var runs = mock(PipelineRunRepository.class);
    var run = new PipelineRun();
    run.setRunId("lease-review");
    run.setCreatedByUserId("actor");
    run.setOrgId("org");
    when(runs.findById("lease-review")).thenReturn(Optional.of(run));
    var routing =
        new VerificationSandboxRoutingService(
            lifecycle, dispatcher, store, properties, runs, mock(SandboxCommandJournalStore.class));
    var authority =
        routing.open(
            "lease-review",
            "b".repeat(64),
            WorkspaceGenerationDispatchAuthority.Phase.VERIFICATION);
    var initial = store.get(authority.sandboxId()).orElseThrow();
    assertThat(initial.state()).isEqualTo(SandboxState.PROVISIONING);
    assertThat(initial.leaseToken()).isPositive();

    jdbc.update(
        "UPDATE run_sandbox SET lease_expires_at=NOW()+INTERVAL '2 seconds' WHERE id=?",
        authority.sandboxId());
    routing.renewSessions();
    var renewed = store.get(authority.sandboxId()).orElseThrow();
    assertThat(renewed.leaseExpiresAt()).isAfter(Instant.now().plusSeconds(90));
    assertThat(store.orphanCandidates(10)).isEmpty();
    assertThat(store.tryAcquire(authority.sandboxId(), "other-jvm", Duration.ofMinutes(2)))
        .isEmpty();
    authority.assertLive();

    jdbc.update(
        "UPDATE run_sandbox SET lease_expires_at=NOW()-INTERVAL '1 second' WHERE id=?",
        authority.sandboxId());
    routing.renewSessions();
    var takeover =
        store.tryAcquire(authority.sandboxId(), "other-jvm", Duration.ofMinutes(2)).orElseThrow();
    assertThat(takeover.fencingToken()).isGreaterThan(initial.leaseToken());
    assertThatThrownBy(authority::assertLive).hasMessageContaining("LEASE_LOST");
    routing.renewSessions();
    assertThat(store.get(authority.sandboxId()).orElseThrow().runtimeOwner())
        .isEqualTo("other-jvm");
  }
}
