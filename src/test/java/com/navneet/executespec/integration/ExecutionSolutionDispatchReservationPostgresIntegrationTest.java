package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.navneet.executespec.execution.service.ExecutionSolutionDispatchRegistry;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Real PostgreSQL proof for approved Execution View child-run reservation and replay.
 *
 * <p>The test deliberately exercises V90's unique {@code (solution_id, attempt_number)} claim and
 * the assigned-UUID {@link ReservedPipelineRunStore} INSERT path. No orchestrator, worker,
 * provider, SCM, or other external side effect is started. Keep this class independently runnable:
 * sharing a cached Testcontainers Spring context with the plan-gate integration subclasses is a
 * known source of false failures in this repository.
 */
@TestPropertySource(
    properties = {
      "execution.plan-gate-resume.scan-initial-delay-ms=3600000",
      "execution.plan-gate-resume.scan-interval-ms=3600000"
    })
class ExecutionSolutionDispatchReservationPostgresIntegrationTest extends PostgresIntegrationBase {

  private static final String PREFIX = "es-dispatch-";

  @Autowired ExecutionSolutionDispatchRegistry registry;
  @Autowired ReservedPipelineRunStore reservedRunStore;
  @Autowired PipelineRunRepository pipelineRuns;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void cleanFixtures() {
    jdbc.update("DELETE FROM pipeline_run WHERE source_id LIKE ?", PREFIX + "%");
    jdbc.update("DELETE FROM execution_run WHERE id LIKE ?", PREFIX + "%");
    jdbc.update("DELETE FROM project WHERE id LIKE ?", PREFIX + "%");
    jdbc.update("DELETE FROM org WHERE id LIKE ?", PREFIX + "%");
  }

  @Test
  void concurrentReservationProducesOneAttemptAndEveryReplayFindsTheCanonicalChild()
      throws Exception {
    Fixture fixture = fixture();
    int callers = 12;
    CountDownLatch ready = new CountDownLatch(callers);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<ExecutionSolutionDispatchRegistry.Reservation>> futures = new ArrayList<>();

    try (var pool = Executors.newFixedThreadPool(callers)) {
      for (int i = 0; i < callers; i++) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  assertThat(start.await(20, TimeUnit.SECONDS)).isTrue();
                  return registry.reserve(fixture.solutionId(), 1);
                }));
      }
      assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      List<ExecutionSolutionDispatchRegistry.Reservation> reservations = new ArrayList<>();
      for (Future<ExecutionSolutionDispatchRegistry.Reservation> future : futures) {
        reservations.add(future.get(60, TimeUnit.SECONDS));
      }
      pool.shutdown();
      assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

      assertThat(reservations)
          .filteredOn(ExecutionSolutionDispatchRegistry.Reservation::newlyReserved)
          .hasSize(1);
      assertThat(reservations)
          .extracting(reservation -> reservation.attempt().getPipelineRunId())
          .containsOnly(reservations.get(0).attempt().getPipelineRunId());
      assertThat(reservations)
          .extracting(reservation -> reservation.attempt().getId())
          .containsOnly(reservations.get(0).attempt().getId());

      String childId = reservations.get(0).attempt().getPipelineRunId();
      ReservedPipelineRunStore.InitialRunPersistence inserted =
          ReservedPipelineRunStore.persistInitial(
              reservedRunStore, pipelineRuns, child(fixture, childId), true);
      assertThat(inserted.created()).isTrue();

      ExecutionSolutionDispatchRegistry.Reservation replay =
          registry.reserve(fixture.solutionId(), 1);
      assertThat(replay.newlyReserved()).isFalse();
      assertThat(replay.attempt().getPipelineRunId()).isEqualTo(childId);
      assertCanonicalBinding(registry.findCanonicalRun(replay).orElseThrow(), fixture, childId);
      assertThat(attemptCount(fixture.solutionId(), 1)).isEqualTo(1);
      assertThat(childCount(childId)).isEqualTo(1);
    }
  }

  @Test
  void crashAfterAttemptReservationThenConcurrentReplayCreatesOnlyTheReservedChild()
      throws Exception {
    Fixture fixture = fixture();

    // The first process wins V90's claim and dies before it persists or dispatches the child.
    ExecutionSolutionDispatchRegistry.Reservation abandoned =
        registry.reserve(fixture.solutionId(), 2);
    String reservedChildId = abandoned.attempt().getPipelineRunId();
    assertThat(abandoned.newlyReserved()).isTrue();
    assertThat(childCount(reservedChildId)).isZero();

    // Every recovery process must recover the already-reserved identity, never mint another UUID.
    ExecutionSolutionDispatchRegistry.Reservation recovered =
        registry.reserve(fixture.solutionId(), 2);
    assertThat(recovered.newlyReserved()).isFalse();
    assertThat(recovered.attempt().getId()).isEqualTo(abandoned.attempt().getId());
    assertThat(recovered.attempt().getPipelineRunId()).isEqualTo(reservedChildId);

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<ReservedPipelineRunStore.InitialRunPersistence>> futures = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(2)) {
      for (int i = 0; i < 2; i++) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  assertThat(start.await(20, TimeUnit.SECONDS)).isTrue();
                  return ReservedPipelineRunStore.persistInitial(
                      reservedRunStore, pipelineRuns, child(fixture, reservedChildId), true);
                }));
      }
      assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      List<ReservedPipelineRunStore.InitialRunPersistence> results = new ArrayList<>();
      for (Future<ReservedPipelineRunStore.InitialRunPersistence> future : futures) {
        results.add(future.get(60, TimeUnit.SECONDS));
      }
      pool.shutdown();
      assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

      assertThat(results)
          .filteredOn(ReservedPipelineRunStore.InitialRunPersistence::created)
          .hasSize(1);
      assertThat(results)
          .extracting(result -> result.run().getRunId())
          .containsOnly(reservedChildId);
    }

    ExecutionSolutionDispatchRegistry.Reservation secondReplay =
        registry.reserve(fixture.solutionId(), 2);
    assertCanonicalBinding(
        registry.findCanonicalRun(secondReplay).orElseThrow(), fixture, reservedChildId);
    assertThat(attemptCount(fixture.solutionId(), 2)).isEqualTo(1);
    assertThat(childCount(reservedChildId)).isEqualTo(1);
  }

  private Fixture fixture() {
    String suffix = UUID.randomUUID().toString();
    String orgId = PREFIX + "org-" + suffix;
    String projectId = PREFIX + "project-" + suffix;
    String executionId = PREFIX + "execution-" + suffix;
    String solutionId = PREFIX + "solution-" + suffix;
    String actorId = PREFIX + "actor-" + suffix;

    jdbc.update(
        "INSERT INTO org (id,name,domain_patterns,tier,created_by_user_id) VALUES (?,?,?,?,?)",
        orgId,
        "Execution dispatch integration org",
        "dispatch.example.test",
        "ENTERPRISE",
        actorId);
    jdbc.update(
        "INSERT INTO project (id,org_id,name,created_by,ai_provider) VALUES (?,?,?,?,?)",
        projectId,
        orgId,
        "Execution dispatch integration project",
        actorId,
        "ANTHROPIC");
    jdbc.update(
        """
        INSERT INTO execution_run
          (id,project_id,org_id,created_by,status,requirement,ai_provider)
        VALUES (?, ?, ?, ?, 'EXECUTING', 'exercise durable child dispatch', 'ANTHROPIC')
        """,
        executionId,
        projectId,
        orgId,
        actorId);
    jdbc.update(
        """
        INSERT INTO execution_solution
          (id,execution_run_id,sequence_number,title,status)
        VALUES (?, ?, 1, 'Approved solution', 'RUNNING')
        """,
        solutionId,
        executionId);
    return new Fixture(solutionId, executionId, projectId, orgId, actorId);
  }

  private static PipelineRun child(Fixture fixture, String runId) {
    return PipelineRun.builder()
        .runId(runId)
        .userRequest("exercise durable child dispatch")
        .status("PLANNING")
        .harnessVersion("v2")
        .sourceType("EXECUTION_VIEW_APPROVED")
        .sourceId(PREFIX + fixture.executionId())
        .createdByUserId(fixture.actorId())
        .orgId(fixture.orgId())
        .billingOrgId(fixture.orgId())
        .projectId(fixture.projectId())
        .startedAt(Instant.now())
        .totalInputTokens(0)
        .totalOutputTokens(0)
        .build();
  }

  private void assertCanonicalBinding(PipelineRun run, Fixture fixture, String childId) {
    assertThat(run.getRunId()).isEqualTo(childId);
    assertThat(run.getSourceType()).isEqualTo("EXECUTION_VIEW_APPROVED");
    assertThat(run.getCreatedByUserId()).isEqualTo(fixture.actorId());
    assertThat(run.getOrgId()).isEqualTo(fixture.orgId());
    assertThat(run.getBillingOrgId()).isEqualTo(fixture.orgId());
    assertThat(run.getProjectId()).isEqualTo(fixture.projectId());
  }

  private int attemptCount(String solutionId, int attemptNumber) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM execution_solution_run WHERE solution_id=? AND attempt_number=?",
            Integer.class,
            solutionId,
            attemptNumber);
    return count == null ? 0 : count;
  }

  private int childCount(String runId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM pipeline_run WHERE run_id=?", Integer.class, runId);
    return count == null ? 0 : count;
  }

  private record Fixture(
      String solutionId, String executionId, String projectId, String orgId, String actorId) {}
}
