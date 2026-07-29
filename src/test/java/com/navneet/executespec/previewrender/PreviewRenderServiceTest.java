package com.navneet.executespec.previewrender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.deployment.WorkspaceRestoreService;
import com.navneet.executespec.executioncontext.LlmExecutionOperationClaim;
import com.navneet.executespec.executioncontext.LlmExecutionOperationCoordinator;
import com.navneet.executespec.executioncontext.LlmExecutionOperationService;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.report.RunChangeEvidenceService;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Covers the async job seam: discovery feeding the agent, guard-rejected output persisting as a
 * reason rather than as servable HTML, and honest terminal states.
 */
class PreviewRenderServiceTest {

  private static final String RUN_ID = "run-123";

  @TempDir Path workspace;

  /** Stateful stand-ins so the async job's real read-modify-write sequence is exercised. */
  private final Map<UUID, PreviewRenderJob> jobStore = new ConcurrentHashMap<>();

  private final List<PreviewRenderBundle> bundleStore =
      Collections.synchronizedList(new ArrayList<>());

  private PreviewRenderJobRepository jobs;
  private PreviewRenderBundleRepository bundles;
  private PreviewRenderAgent agent;
  private FeatureFlagService flags;
  private WorkspaceRestoreService restore;
  private RunChangeEvidenceService changes;
  private StateStore stateStore;
  private PreviewRenderService service;

  @BeforeEach
  void setUp() throws Exception {
    jobs = mock(PreviewRenderJobRepository.class);
    when(jobs.save(any()))
        .thenAnswer(
            inv -> {
              PreviewRenderJob j = inv.getArgument(0);
              jobStore.put(j.getId(), j);
              return j;
            });
    when(jobs.findById(any()))
        .thenAnswer(inv -> Optional.ofNullable(jobStore.get(inv.getArgument(0))));
    when(jobs.saveAndFlush(any()))
        .thenAnswer(
            inv -> {
              PreviewRenderJob j = inv.getArgument(0);
              jobStore.put(j.getId(), j);
              return j;
            });
    when(jobs.findActiveForRun(anyString())).thenAnswer(inv -> activeJobsFor(inv.getArgument(0)));

    // Lease transitions are single atomic statements in Postgres; mirror that here so the async
    // path exercises real claim / renew / fenced-release semantics rather than a no-op.
    when(jobs.claimLease(any(), anyString(), any()))
        .thenAnswer(
            inv -> {
              PreviewRenderJob j = jobStore.get(inv.<UUID>getArgument(0));
              if (j == null || !PreviewRenderJob.Status.QUEUED.name().equals(j.getStatus())) {
                return 0;
              }
              j.setStatus(PreviewRenderJob.Status.RENDERING.name());
              j.setLeaseOwner(inv.getArgument(1));
              j.setLeaseExpiresAt(inv.getArgument(2));
              return 1;
            });
    when(jobs.renewLease(any(), anyString(), any()))
        .thenAnswer(
            inv -> {
              PreviewRenderJob j = jobStore.get(inv.<UUID>getArgument(0));
              if (j == null || !inv.getArgument(1).equals(j.getLeaseOwner())) {
                return 0;
              }
              j.setLeaseExpiresAt(inv.getArgument(2));
              return 1;
            });
    when(jobs.releaseLease(any(), anyString(), anyString(), any(), anyInt(), anyInt(), any()))
        .thenAnswer(
            inv -> {
              PreviewRenderJob j = jobStore.get(inv.<UUID>getArgument(0));
              if (j == null || !inv.getArgument(1).equals(j.getLeaseOwner())) {
                return 0;
              }
              j.setStatus(inv.getArgument(2));
              j.setFailureReason(inv.getArgument(3));
              j.setSurfacesFound(inv.getArgument(4));
              j.setSurfacesRendered(inv.getArgument(5));
              j.setCompletedAt(inv.getArgument(6));
              j.setLeaseOwner(null);
              j.setLeaseExpiresAt(null);
              return 1;
            });
    when(jobs.reclaimAbandonedForRun(anyString(), any(), any()))
        .thenAnswer(
            inv ->
                reclaim(activeJobsFor(inv.getArgument(0)), inv.getArgument(1), inv.getArgument(2)));
    when(jobs.reclaimAbandoned(any(), any()))
        .thenAnswer(
            inv ->
                reclaim(
                    jobStore.values().stream().filter(j -> !j.isTerminal()).toList(),
                    inv.getArgument(0),
                    inv.getArgument(1)));

    bundles = mock(PreviewRenderBundleRepository.class);
    when(bundles.save(any()))
        .thenAnswer(
            inv -> {
              PreviewRenderBundle b = inv.getArgument(0);
              bundleStore.add(b);
              return b;
            });
    when(bundles.findByJobIdOrderByDisplayOrderAsc(any()))
        .thenAnswer(
            inv ->
                bundleStore.stream()
                    .filter(b -> b.getJobId().equals(inv.getArgument(0)))
                    .sorted(java.util.Comparator.comparingInt(PreviewRenderBundle::getDisplayOrder))
                    .toList());

    agent = mock(PreviewRenderAgent.class);
    flags = mock(FeatureFlagService.class);
    restore = mock(WorkspaceRestoreService.class);

    stateStore = mock(StateStore.class);
    changes = mock(RunChangeEvidenceService.class);

    PipelineRun run = new PipelineRun();
    run.setAiProvider(LlmProvider.ANTHROPIC.name());
    when(stateStore.findRun(RUN_ID)).thenReturn(Optional.of(run));
    when(flags.isEnabled(PreviewRenderService.FLAG)).thenReturn(true);
    when(restore.ensureWorkspaceAvailable(RUN_ID)).thenReturn(workspace);
    when(changes.meaningfulProductDiffCount(RUN_ID)).thenReturn(1);
    // An invalid diff must degrade the ranking signal, not fail the render.
    when(changes.inspect(anyString(), any())).thenReturn(null);

    service =
        new PreviewRenderService(
            new PreviewSurfaceDiscovery(),
            agent,
            jobs,
            bundles,
            restore,
            changes,
            stateStore,
            flags);

    configure(service);
  }

  @Test
  void rejectsRequestWhileFlagIsOff() {
    when(flags.isEnabled(PreviewRenderService.FLAG)).thenReturn(false);

    assertThatThrownBy(() -> service.requestRender(RUN_ID, "user"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("not enabled");

    assertThat(jobStore).isEmpty();
    verify(changes, never()).meaningfulProductDiffCount(anyString());
  }

  @Test
  void availabilityFailsClosedWhenTheFlagStoreCannotBeRead() {
    when(flags.isEnabled(PreviewRenderService.FLAG))
        .thenThrow(new IllegalStateException("flag store unavailable"));

    assertThat(service.enabled()).isFalse();
  }

  @Test
  void rejectsRenderWhenRunProducedNoProductFiles() {
    when(changes.meaningfulProductDiffCount(RUN_ID)).thenReturn(0);

    assertThatThrownBy(() -> service.requestRender(RUN_ID, "user"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("produced no product files");

    assertThat(jobStore).isEmpty();
    verify(agent, never()).render(any(), any(), anyString(), anyString());
  }

  @Test
  void rendersDiscoveredScreensAndReachesReady() throws Exception {
    writeScreen("src/pages/Home.tsx", "Home");
    writeScreen("src/pages/Settings.tsx", "Settings");

    when(agent.render(any(), any(), anyString(), anyString()))
        .thenAnswer(
            inv -> {
              PreviewSurface s = inv.getArgument(0);
              return new PreviewRenderAgent.RenderedSurface(
                  s.id(), "<html><body><div>" + s.displayName() + "</div></body></html>", null);
            });

    PreviewRenderJob job = service.requestRender(RUN_ID, "user");
    awaitTerminal(job);

    PreviewRenderJob done = jobs.findById(job.getId()).orElseThrow();
    assertThat(done.getStatus()).isEqualTo(PreviewRenderJob.Status.READY.name());
    assertThat(done.getSurfacesFound()).isEqualTo(2);
    assertThat(done.getSurfacesRendered()).isEqualTo(2);
    assertThat(done.getCompletedAt()).isNotNull();

    List<PreviewRenderBundle> saved = bundles.findByJobIdOrderByDisplayOrderAsc(job.getId());
    assertThat(saved).hasSize(2);
    assertThat(saved).allSatisfy(b -> assertThat(b.getHtml()).contains("<html>"));
    assertThat(saved)
        .extracting(PreviewRenderBundle::getRenderTarget)
        .containsOnly(PreviewRenderTarget.WEB.name());
  }

  @Test
  void durableSurfaceCompletesOperationOnlyAfterBundlePersistence() throws Exception {
    writeScreen("src/pages/Home.tsx", "Home");
    LlmExecutionPolicyFactory policies = mock(LlmExecutionPolicyFactory.class);
    LlmExecutionOperationService operationService = mock(LlmExecutionOperationService.class);
    LlmExecutionReference reference =
        new LlmExecutionReference("ctx-preview", LlmExecutionScope.customer("org"));
    LlmExecutionOperationClaim claim =
        new LlmExecutionOperationClaim(reference, "llmop-preview", 1, 1, UUID.randomUUID());
    when(policies.forRunPreviewSurface(eq(RUN_ID), anyString())).thenReturn(reference);
    when(operationService.claimOrResume(any(), any(), anyString(), any()))
        .thenReturn(Optional.of(claim));
    when(operationService.complete(eq(claim), anyString())).thenReturn(true);
    PreviewRenderOperationRoot operationRoot =
        new PreviewRenderOperationRoot(
            policies, new LlmExecutionOperationCoordinator(operationService));
    ResolvedModel route =
        ResolvedModel.builder()
            .requestedProvider(LlmProvider.ANTHROPIC)
            .actualProvider(LlmProvider.ANTHROPIC)
            .model("claude-sonnet")
            .agentTier(AgentTier.WORKHORSE)
            .build();
    when(agent.resolveRoute(LlmProvider.ANTHROPIC)).thenReturn(route);
    when(agent.render(any(), eq(route), eq(RUN_ID), anyString(), any()))
        .thenAnswer(
            invocation -> {
              PreviewSurface surface = invocation.getArgument(0);
              return new PreviewRenderAgent.RenderedSurface(
                  surface.id(), "<html><body>Home</body></html>", null);
            });
    service =
        new PreviewRenderService(
            new PreviewSurfaceDiscovery(),
            agent,
            jobs,
            bundles,
            restore,
            changes,
            stateStore,
            flags,
            operationRoot);
    configure(service);

    PreviewRenderJob job = service.requestRender(RUN_ID, "user");
    awaitTerminal(job);

    var order = org.mockito.Mockito.inOrder(bundles, operationService);
    order.verify(bundles).save(any(PreviewRenderBundle.class));
    order.verify(operationService).complete(eq(claim), anyString());
  }

  @Test
  void persistsRejectedSurfaceAsReasonWithoutStoringItsHtml() throws Exception {
    writeScreen("src/pages/Home.tsx", "Home");

    when(agent.render(any(), any(), anyString(), anyString()))
        .thenAnswer(
            inv ->
                new PreviewRenderAgent.RenderedSurface(
                    ((PreviewSurface) inv.getArgument(0)).id(),
                    null,
                    "EXTERNAL_NETWORK_REFERENCE"));

    PreviewRenderJob job = service.requestRender(RUN_ID, "user");
    awaitTerminal(job);

    PreviewRenderJob done = jobs.findById(job.getId()).orElseThrow();
    assertThat(done.getStatus()).isEqualTo(PreviewRenderJob.Status.FAILED.name());
    assertThat(done.getFailureReason()).isEqualTo("ALL_SURFACES_FAILED");

    PreviewRenderBundle bundle = bundles.findByJobIdOrderByDisplayOrderAsc(job.getId()).get(0);
    assertThat(bundle.getStatus()).isEqualTo(PreviewRenderBundle.Status.FAILED.name());
    assertThat(bundle.getFailureReason()).isEqualTo("EXTERNAL_NETWORK_REFERENCE");
    // The whole point of the rejection: nothing servable survives.
    assertThat(bundle.getHtml()).isNull();
  }

  @Test
  void reportsNoSurfacesWithoutCallingTheModel() throws Exception {
    Files.writeString(workspace.resolve("README.md"), "no ui here");

    PreviewRenderJob job = service.requestRender(RUN_ID, "user");
    awaitTerminal(job);

    assertThat(jobs.findById(job.getId()).orElseThrow().getStatus())
        .isEqualTo(PreviewRenderJob.Status.NO_SURFACES.name());
    // A run with no UI must never spend a provider call.
    verify(agent, never()).render(any(), any(), anyString(), anyString());
  }

  @Test
  void failsClosedWhenWorkspaceIsGone() {
    when(restore.ensureWorkspaceAvailable(RUN_ID)).thenThrow(new IllegalStateException("archived"));

    PreviewRenderJob job = service.requestRender(RUN_ID, "user");
    awaitTerminal(job);

    PreviewRenderJob done = jobs.findById(job.getId()).orElseThrow();
    assertThat(done.getStatus()).isEqualTo(PreviewRenderJob.Status.FAILED.name());
    assertThat(done.getFailureReason()).isEqualTo("WORKSPACE_UNAVAILABLE");
    verify(agent, never()).render(any(), any(), anyString(), anyString());
  }

  @Test
  void returnsTheInFlightJobInsteadOfStartingASecond() throws Exception {
    writeScreen("src/pages/Home.tsx", "Home");
    when(agent.render(any(), any(), anyString(), anyString()))
        .thenAnswer(
            inv -> {
              Thread.sleep(400);
              return new PreviewRenderAgent.RenderedSurface(
                  ((PreviewSurface) inv.getArgument(0)).id(),
                  "<html><body><p>x</p></body></html>",
                  null);
            });

    PreviewRenderJob first = service.requestRender(RUN_ID, "user");
    PreviewRenderJob second = service.requestRender(RUN_ID, "user");

    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(jobStore).hasSize(1);
    awaitTerminal(first);
  }

  @Test
  void returnsAnExistingActiveJobBeforeApplyingTheProductFileGate() {
    PreviewRenderJob active =
        PreviewRenderJob.builder()
            .runId(RUN_ID)
            .status(PreviewRenderJob.Status.RENDERING.name())
            .requestedAt(Instant.now())
            .leaseOwner("healthy-instance")
            .leaseExpiresAt(Instant.now().plus(Duration.ofMinutes(5)))
            .build();
    jobStore.put(active.getId(), active);
    when(changes.meaningfulProductDiffCount(RUN_ID)).thenReturn(0);

    assertThat(service.requestRender(RUN_ID, "user").getId()).isEqualTo(active.getId());
    verify(changes, never()).meaningfulProductDiffCount(anyString());
  }

  /**
   * D8 regression. A backend restart mid-render leaves the row RENDERING with nobody executing it.
   * Without lease expiry that row stays active forever, every later request returns it, and the run
   * can never be rendered again — a permanent, user-visible wedge.
   */
  @Test
  void reclaimsARenderWedgedByARestartOnceItsLeaseHasExpired() throws Exception {
    writeScreen("src/pages/Home.tsx", "Home");
    stubSuccessfulRender();

    PreviewRenderJob wedged =
        PreviewRenderJob.builder()
            .runId(RUN_ID)
            .status(PreviewRenderJob.Status.RENDERING.name())
            .requestedAt(Instant.now().minus(Duration.ofHours(2)))
            .leaseOwner("instance-that-died")
            .leaseExpiresAt(Instant.now().minus(Duration.ofHours(1)))
            .build();
    jobStore.put(wedged.getId(), wedged);

    PreviewRenderJob fresh = service.requestRender(RUN_ID, "user");

    assertThat(fresh.getId()).isNotEqualTo(wedged.getId());
    assertThat(jobStore.get(wedged.getId()).isTerminal()).isTrue();
    awaitTerminal(fresh);
    assertThat(jobs.findById(fresh.getId()).orElseThrow().getStatus())
        .isEqualTo(PreviewRenderJob.Status.READY.name());
  }

  /** The reclaim must key on lease expiry, not on age, or it would kill a healthy long render. */
  @Test
  void leavesAJobAloneWhileItsLeaseIsStillHeld() {
    PreviewRenderJob live =
        PreviewRenderJob.builder()
            .runId(RUN_ID)
            .status(PreviewRenderJob.Status.RENDERING.name())
            .requestedAt(Instant.now().minus(Duration.ofMinutes(20)))
            .leaseOwner("healthy-instance")
            .leaseExpiresAt(Instant.now().plus(Duration.ofMinutes(5)))
            .build();
    jobStore.put(live.getId(), live);

    assertThat(service.requestRender(RUN_ID, "user").getId()).isEqualTo(live.getId());
    assertThat(jobStore).hasSize(1);
  }

  /**
   * D7 regression. Single-flight cannot rest on a read-then-write: two requests can both pass the
   * active-job check and both spend managed AI credits. The database rejects the second insert, and
   * the loser must be handed the winner's job rather than an error.
   */
  @Test
  void yieldsTheWinningJobWhenTheDatabaseRejectsAConcurrentSecondJob() throws Exception {
    writeScreen("src/pages/Home.tsx", "Home");
    CountDownLatch renderStarted = new CountDownLatch(1);
    CountDownLatch releaseRender = new CountDownLatch(1);
    when(agent.render(any(), any(), anyString(), anyString()))
        .thenAnswer(
            inv -> {
              renderStarted.countDown();
              if (!releaseRender.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release the winning render");
              }
              return new PreviewRenderAgent.RenderedSurface(
                  ((PreviewSurface) inv.getArgument(0)).id(),
                  "<html><body><div>ok</div></body></html>",
                  null);
            });
    enforceOneActiveJobPerRun();

    // Both callers observe an empty active set, exactly as an interleaved read-then-write does.
    doReturn(List.of(), List.of())
        .doAnswer(inv -> activeJobsFor(inv.getArgument(0)))
        .when(jobs)
        .findActiveForRun(anyString());

    PreviewRenderJob first = service.requestRender(RUN_ID, "user");
    assertThat(renderStarted.await(5, TimeUnit.SECONDS)).isTrue();

    try {
      PreviewRenderJob second = service.requestRender(RUN_ID, "user");
      assertThat(second.getId()).isEqualTo(first.getId());
      assertThat(jobStore).hasSize(1);
    } finally {
      releaseRender.countDown();
    }
    awaitTerminal(first);
  }

  // ---------------------------------------------------------------- helpers

  private static void configure(PreviewRenderService service) {
    ReflectionTestUtils.setField(service, "maxSurfaces", 6);
    ReflectionTestUtils.setField(service, "characterBudget", 200_000);
    ReflectionTestUtils.setField(service, "parallelism", 2);
    ReflectionTestUtils.setField(service, "timeoutSeconds", 20);
    ReflectionTestUtils.setField(service, "leaseSeconds", 120);
  }

  /** Stands in for the partial unique index on (run_id) WHERE status IN ('QUEUED','RENDERING'). */
  private void enforceOneActiveJobPerRun() {
    org.mockito.stubbing.Answer<PreviewRenderJob> guarded =
        inv -> {
          PreviewRenderJob incoming = inv.getArgument(0);
          boolean clash =
              jobStore.values().stream()
                  .anyMatch(
                      j ->
                          !j.getId().equals(incoming.getId())
                              && j.getRunId().equals(incoming.getRunId())
                              && !j.isTerminal()
                              && !incoming.isTerminal());
          if (clash) {
            throw new DataIntegrityViolationException("uq_preview_render_job_active_run");
          }
          jobStore.put(incoming.getId(), incoming);
          return incoming;
        };
    doAnswer(guarded).when(jobs).save(any());
    doAnswer(guarded).when(jobs).saveAndFlush(any());
  }

  /** Mirrors the reclaim predicate: an expired lease, or a QUEUED job nobody ever claimed. */
  private int reclaim(List<PreviewRenderJob> candidates, Instant now, Instant queuedCutoff) {
    int reclaimed = 0;
    for (PreviewRenderJob j : candidates) {
      boolean expired = j.getLeaseExpiresAt() != null && j.getLeaseExpiresAt().isBefore(now);
      boolean neverClaimed =
          j.getLeaseExpiresAt() == null && j.getRequestedAt().isBefore(queuedCutoff);
      if (expired || neverClaimed) {
        j.setStatus(PreviewRenderJob.Status.FAILED.name());
        j.setFailureReason("LEASE_EXPIRED");
        j.setCompletedAt(now);
        j.setLeaseOwner(null);
        j.setLeaseExpiresAt(null);
        reclaimed++;
      }
    }
    return reclaimed;
  }

  private List<PreviewRenderJob> activeJobsFor(String runId) {
    return jobStore.values().stream()
        .filter(j -> j.getRunId().equals(runId) && !j.isTerminal())
        .toList();
  }

  private void stubSuccessfulRender() {
    when(agent.render(any(), any(), anyString(), anyString()))
        .thenAnswer(
            inv ->
                new PreviewRenderAgent.RenderedSurface(
                    ((PreviewSurface) inv.getArgument(0)).id(),
                    "<html><body><div>ok</div></body></html>",
                    null));
  }

  private void writeScreen(String relative, String name) throws Exception {
    Path target = workspace.resolve(relative);
    Files.createDirectories(target.getParent());
    Files.writeString(
        target, "export default function " + name + "(){ return (<div>" + name + "</div>); }");
  }

  /** The job runs on a virtual thread, so poll rather than assuming completion. */
  private void awaitTerminal(PreviewRenderJob job) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
    while (Instant.now().isBefore(deadline)) {
      if (jobs.findById(job.getId()).map(PreviewRenderJob::isTerminal).orElse(false)) {
        return;
      }
      try {
        Thread.sleep(25);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    throw new AssertionError(
        "job did not terminalize; last status="
            + jobs.findById(job.getId()).map(PreviewRenderJob::getStatus).orElse("MISSING"));
  }
}
