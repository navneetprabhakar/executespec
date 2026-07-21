package com.navneet.executespec.previewrender;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.deployment.WorkspaceRestoreService;
import com.navneet.executespec.report.RunChangeEvidenceService;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import io.micrometer.context.ContextSnapshotFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates on-demand static UI renders for a run.
 *
 * <p>Nothing here deploys, builds, or starts a container. Discovery reads the workspace, the agent
 * turns each screen into an offline HTML document, and the guard admits or rejects it. Because the
 * render never executes the product, it works on runs whose build is red — which is the case the
 * container preview cannot serve at all.
 *
 * <p>The output is decorative by construction: no scorer reads these tables, and the service
 * publishes no validation evidence.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreviewRenderService {

  public static final String FLAG = "preview.render-agent.enabled";

  private final PreviewSurfaceDiscovery discovery;
  private final PreviewRenderAgent renderAgent;
  private final PreviewRenderJobRepository jobRepository;
  private final PreviewRenderBundleRepository bundleRepository;
  private final WorkspaceRestoreService workspaceRestoreService;
  private final RunChangeEvidenceService changeEvidenceService;
  private final StateStore stateStore;
  private final FeatureFlagService featureFlagService;

  @Value("${preview.render.max-surfaces:6}")
  private int maxSurfaces;

  @Value("${preview.render.character-budget:180000}")
  private int characterBudget;

  @Value("${preview.render.parallelism:3}")
  private int parallelism;

  @Value("${preview.render.timeout-seconds:300}")
  private int timeoutSeconds;

  /**
   * How long a claimed render stays owned without renewal. Short, because it is also the worst-case
   * time a run stays blocked after the instance rendering it dies.
   */
  @Value("${preview.render.lease-seconds:120}")
  private int leaseSeconds;

  /** Identifies this JVM in {@code lease_owner}. Regenerated per process, which is the point. */
  private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

  /** Whether the feature is available at all. */
  public boolean enabled() {
    try {
      return featureFlagService.isEnabled(FLAG);
    } catch (RuntimeException e) {
      log.warn("preview-render flag lookup failed; treating as disabled", e);
      return false;
    }
  }

  /**
   * Starts a render for a run, or returns the in-flight job if one is already running.
   *
   * <p>Single-flight is a database fact, not an application check. The read below is an
   * optimization; the guarantee is V306's partial unique index on the active state, because two
   * requests can interleave between any read and its write — and on this path that would spend
   * managed AI credits twice for one run. An in-JVM lock would not help across instances.
   *
   * @return the job, already persisted in QUEUED or RENDERING state
   */
  public PreviewRenderJob requestRender(String runId, String requestedBy) {
    if (!enabled()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "preview render is not enabled");
    }

    // A render abandoned by a restart would otherwise stay active forever and block this run.
    reclaimAbandonedFor(runId);

    List<PreviewRenderJob> active = jobRepository.findActiveForRun(runId);
    if (!active.isEmpty()) {
      return active.get(0);
    }

    PipelineRun run =
        stateStore
            .findRun(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "run not found"));

    PreviewRenderJob job;
    try {
      job =
          jobRepository.saveAndFlush(
              PreviewRenderJob.builder()
                  .runId(runId)
                  .requestedBy(requestedBy)
                  .status(PreviewRenderJob.Status.QUEUED.name())
                  .provider(run.getAiProvider())
                  .build());
    } catch (DataIntegrityViolationException e) {
      // Lost the race. The winner's job is the single flight; hand the caller that one.
      log.debug("preview-render lost the single-flight race run={}", runId, e);
      return jobRepository.findActiveForRun(runId).stream()
          .findFirst()
          .orElseThrow(
              () ->
                  new ResponseStatusException(
                      HttpStatus.CONFLICT, "a render is already in progress"));
    }

    var snapshot = ContextSnapshotFactory.builder().build().captureAll();
    Thread.ofVirtual()
        .name("preview-render-" + runId)
        .start(snapshot.wrap(() -> executeJob(job.getId(), runId, run.getAiProviderEnum())));

    return job;
  }

  /**
   * Terminalizes renders that no instance is executing any more.
   *
   * <p>Runs periodically as well as on request: a wedged job keeps the client polling and its
   * button disabled, so the run cannot heal itself through a new request alone.
   */
  @Scheduled(
      initialDelayString = "${preview.render.sweep-interval-ms:120000}",
      fixedDelayString = "${preview.render.sweep-interval-ms:120000}")
  public void sweepAbandonedRenders() {
    try {
      Instant now = Instant.now();
      int reclaimed = jobRepository.reclaimAbandoned(now, queuedCutoff(now));
      if (reclaimed > 0) {
        log.info("preview-render reclaimed {} abandoned render job(s)", reclaimed);
      }
    } catch (RuntimeException e) {
      log.warn("preview-render sweep failed", e);
    }
  }

  private void reclaimAbandonedFor(String runId) {
    try {
      Instant now = Instant.now();
      int reclaimed = jobRepository.reclaimAbandonedForRun(runId, now, queuedCutoff(now));
      if (reclaimed > 0) {
        log.info("preview-render reclaimed {} abandoned render job(s) run={}", reclaimed, runId);
      }
    } catch (RuntimeException e) {
      // A failed reclaim must not block the request; the sweeper retries.
      log.warn("preview-render reclaim failed run={}", runId, e);
    }
  }

  /** A QUEUED job never picked up within a lease window belongs to an instance that died. */
  private Instant queuedCutoff(Instant now) {
    return now.minusSeconds(Math.max(30, leaseSeconds));
  }

  /** Latest job for a run, if any. */
  public Optional<PreviewRenderJob> latestJob(String runId) {
    return jobRepository.findFirstByRunIdOrderByRequestedAtDesc(runId);
  }

  /** Surfaces belonging to a job, in display order. */
  public List<PreviewRenderBundle> bundles(UUID jobId) {
    return bundleRepository.findByJobIdOrderByDisplayOrderAsc(jobId);
  }

  /** A single admitted bundle, for serving into the sandboxed frame. */
  public Optional<PreviewRenderBundle> bundle(UUID jobId, String surfaceId) {
    return bundleRepository.findByJobIdAndSurfaceId(jobId, surfaceId);
  }

  // ---------------------------------------------------------------- execution

  /** The terminal state of one render, before it is written back under the lease. */
  private record Outcome(PreviewRenderJob.Status status, String reason, int found, int rendered) {
    static Outcome of(PreviewRenderJob.Status status, String reason) {
      return new Outcome(status, reason, 0, 0);
    }
  }

  private void executeJob(UUID jobId, String runId, LlmProvider provider) {
    String owner = instanceId + ":" + UUID.randomUUID();
    if (jobRepository.claimLease(jobId, owner, Instant.now().plusSeconds(leaseSeconds)) == 0) {
      // Another instance reclaimed or is already running this job. Do not render it twice.
      log.info("preview-render job already claimed elsewhere run={} job={}", runId, jobId);
      return;
    }

    AtomicBoolean running = new AtomicBoolean(true);
    Thread renewal = startLeaseRenewal(jobId, owner, running);
    Outcome outcome;
    try {
      outcome = renderJob(jobId, runId, provider);
    } catch (RuntimeException e) {
      log.error("preview-render job failed run={} job={}", runId, jobId, e);
      outcome = Outcome.of(PreviewRenderJob.Status.FAILED, "RENDER_JOB_ERROR");
    } finally {
      running.set(false);
      renewal.interrupt();
    }

    int released =
        jobRepository.releaseLease(
            jobId,
            owner,
            outcome.status().name(),
            outcome.reason(),
            outcome.found(),
            outcome.rendered(),
            Instant.now());
    if (released == 0) {
      // The lease lapsed mid-render and someone else reclaimed the job. Theirs is the record.
      log.warn("preview-render lost its lease before finishing run={} job={}", runId, jobId);
    }
  }

  private Outcome renderJob(UUID jobId, String runId, LlmProvider provider) {
    Path workspace;
    try {
      workspace = workspaceRestoreService.ensureWorkspaceAvailable(runId);
    } catch (IllegalStateException e) {
      log.info("preview-render workspace unavailable run={}", runId, e);
      return Outcome.of(PreviewRenderJob.Status.FAILED, "WORKSPACE_UNAVAILABLE");
    }

    List<PreviewSurface> surfaces =
        discovery.discover(workspace, changedProductPaths(runId), maxSurfaces, characterBudget);
    if (surfaces.isEmpty()) {
      return Outcome.of(PreviewRenderJob.Status.NO_SURFACES, null);
    }

    int rendered = renderSurfaces(jobId, runId, provider, surfaces);
    return new Outcome(
        rendered > 0 ? PreviewRenderJob.Status.READY : PreviewRenderJob.Status.FAILED,
        rendered > 0 ? null : "ALL_SURFACES_FAILED",
        surfaces.size(),
        rendered);
  }

  /** Renders every discovered surface, persisting each result. Returns how many were admitted. */
  private int renderSurfaces(
      UUID jobId, String runId, LlmProvider provider, List<PreviewSurface> surfaces) {

    ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallelism));
    try {
      var snapshot = ContextSnapshotFactory.builder().build().captureAll();
      List<Future<PreviewRenderAgent.RenderedSurface>> futures = new ArrayList<>();
      for (PreviewSurface surface : surfaces) {
        futures.add(pool.submit(snapshot.wrap(() -> renderAgent.render(surface, provider, runId))));
      }

      int rendered = 0;
      for (int i = 0; i < surfaces.size(); i++) {
        PreviewSurface surface = surfaces.get(i);
        PreviewRenderAgent.RenderedSurface result;
        try {
          result = futures.get(i).get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
          log.warn("preview-render surface timed out run={} surface={}", runId, surface.id(), e);
          futures.get(i).cancel(true);
          result = new PreviewRenderAgent.RenderedSurface(surface.id(), null, "TIMEOUT");
        }
        if (result.ok()) {
          rendered++;
        }
        persistSurface(jobId, runId, surface, result, i);
      }
      return rendered;
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Keeps the lease alive while the render runs. A render can take several minutes, and the lease
   * has to be short enough that a crash heals quickly — so it is renewed rather than pre-sized.
   */
  private Thread startLeaseRenewal(UUID jobId, String owner, AtomicBoolean running) {
    return Thread.ofVirtual()
        .name("preview-render-lease-" + jobId)
        .start(
            () -> {
              while (running.get()) {
                try {
                  Thread.sleep(Duration.ofSeconds(Math.max(5, leaseSeconds / 3)));
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                if (!running.get()) {
                  return;
                }
                try {
                  if (jobRepository.renewLease(
                          jobId, owner, Instant.now().plusSeconds(leaseSeconds))
                      == 0) {
                    log.warn("preview-render lease no longer held job={}", jobId);
                    return;
                  }
                } catch (RuntimeException e) {
                  log.warn("preview-render lease renewal failed job={}", jobId, e);
                }
              }
            });
  }

  /**
   * The run's own changed product files, used to rank its work above pre-existing repo screens.
   * Fails soft: on an invalid diff the render simply loses that ranking signal.
   */
  private Set<String> changedProductPaths(String runId) {
    try {
      var evidence = changeEvidenceService.inspect(runId, List.of());
      if (evidence == null || !evidence.valid()) {
        return Set.of();
      }
      Set<String> out = new HashSet<>();
      evidence.productFiles().forEach(f -> out.add(f.path()));
      return out;
    } catch (RuntimeException e) {
      log.debug("preview-render change evidence unavailable run={}", runId, e);
      return Set.of();
    }
  }

  private void persistSurface(
      UUID jobId,
      String runId,
      PreviewSurface surface,
      PreviewRenderAgent.RenderedSurface result,
      int order) {
    try {
      bundleRepository.save(
          PreviewRenderBundle.builder()
              .jobId(jobId)
              .runId(runId)
              .surfaceId(surface.id())
              .sourcePath(surface.path())
              .displayName(surface.displayName())
              .renderTarget(surface.target().name())
              .fidelity(surface.target().fidelity().name())
              .viewportWidth(surface.target().defaultWidth())
              .viewportHeight(surface.target().defaultHeight())
              .status(
                  result.ok()
                      ? PreviewRenderBundle.Status.RENDERED.name()
                      : PreviewRenderBundle.Status.FAILED.name())
              // Rejected output is never persisted, only its category.
              .html(result.ok() ? result.html() : null)
              .failureReason(result.failureReason())
              .displayOrder(order)
              .build());
    } catch (RuntimeException e) {
      log.warn("preview-render could not persist surface={} run={}", surface.id(), runId, e);
    }
  }
}
