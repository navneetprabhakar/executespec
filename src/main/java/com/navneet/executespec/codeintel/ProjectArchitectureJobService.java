package com.navneet.executespec.codeintel;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.executioncontext.LlmExecutionBudget;
import com.navneet.executespec.executioncontext.LlmExecutionContextService;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.repo.ScmCredentialResolver;
import com.navneet.executespec.repo.ScmProvider;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ProjectProviderResolver;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/** DB-local admission plus durable PREPARING for project architecture refresh jobs. */
@Service
@RequiredArgsConstructor
public class ProjectArchitectureJobService {

  public record SourceRepo(
      UUID repoId,
      String scmProvider,
      String sourceKey,
      String repoFullName,
      String branch,
      String revision,
      String credentialSubjectUserId) {}

  public record SourceSnapshot(
      int schemaVersion, String projectId, List<SourceRepo> repositories) {}

  public record Prepared(SourceSnapshot snapshot, String canonicalJson, String digest) {}

  private final ProjectArchitectureJobStore store;
  private final ProjectRepository projectRepository;
  private final ProjectRepoLinkRepository links;
  private final ProjectRepoRepository repos;
  private final ScmCredentialResolver credentialResolver;
  private final GitHubRepoService github;
  private final ProjectProviderResolver providerResolver;
  private final ModelResolver modelResolver;
  private final LlmExecutionPolicyFactory executionPolicyFactory;
  private final LlmExecutionContextService executionContexts;
  private final ProjectArchitectureJobProperties properties;
  private final ObjectMapper objectMapper;

  /**
   * HTTP admission is intentionally DB/config-local: no credential lookup, SCM HEAD, provider call,
   * workspace write, or execution-context creation occurs before the controller returns 202.
   */
  public ProjectArchitectureJobStore.Job request(String projectId, String actorUserId) {
    return request(projectId, actorUserId, null);
  }

  public ProjectArchitectureJobStore.Job request(
      String projectId, String actorUserId, String branchOverride) {
    Project project =
        projectRepository
            .findById(projectId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    LlmProvider requested = providerResolver.resolve(project);
    var repoRoute = modelResolver.resolve("RepoArchitectureGenerator", requested);
    var projectRoute = modelResolver.resolve("ArchitectureDocGenerator", requested);
    if (repoRoute.getActualProvider() != projectRoute.getActualProvider()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Architecture routes do not resolve to one durable provider");
    }
    return requireCompatible(
        store.createOrGet(
            UUID.randomUUID(),
            projectId,
            project.getOrgId(),
            actorUserId,
            branchOverride == null || branchOverride.isBlank() ? null : branchOverride.trim(),
            projectRoute.getActualProvider().name(),
            repoRoute.getModel(),
            projectRoute.getModel(),
            Duration.ofSeconds(properties.getDeadlineSeconds())),
        actorUserId,
        branchOverride,
        projectRoute.getActualProvider(),
        repoRoute.getModel(),
        projectRoute.getModel());
  }

  /**
   * Confirmed action admission: persist the already-authorized exact route without re-resolving.
   */
  public ProjectArchitectureJobStore.Job requestExact(
      String projectId,
      String actorUserId,
      String branchOverride,
      LlmProvider provider,
      String exactModel) {
    if (provider == null || exactModel == null || exactModel.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Exact architecture provider/model route is required");
    }
    Project project =
        projectRepository
            .findById(projectId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    return requireCompatible(
        store.createOrGet(
            UUID.randomUUID(),
            projectId,
            project.getOrgId(),
            actorUserId,
            branchOverride == null || branchOverride.isBlank() ? null : branchOverride.trim(),
            provider.name(),
            exactModel.trim(),
            exactModel.trim(),
            Duration.ofSeconds(properties.getDeadlineSeconds())),
        actorUserId,
        branchOverride,
        provider,
        exactModel,
        exactModel);
  }

  /** Run-scoped admission with an explicit provider; tier models remain independently resolved. */
  public ProjectArchitectureJobStore.Job requestWithProvider(
      String projectId, String actorUserId, String branchOverride, LlmProvider provider) {
    Project project =
        projectRepository
            .findById(projectId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    var repoRoute = modelResolver.resolve("RepoArchitectureGenerator", provider);
    var projectRoute = modelResolver.resolve("ArchitectureDocGenerator", provider);
    if (repoRoute.getActualProvider() != projectRoute.getActualProvider()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Architecture routes do not resolve to one durable provider");
    }
    return requireCompatible(
        store.createOrGet(
            UUID.randomUUID(),
            projectId,
            project.getOrgId(),
            actorUserId,
            branchOverride == null || branchOverride.isBlank() ? null : branchOverride.trim(),
            projectRoute.getActualProvider().name(),
            repoRoute.getModel(),
            projectRoute.getModel(),
            Duration.ofSeconds(properties.getDeadlineSeconds())),
        actorUserId,
        branchOverride,
        projectRoute.getActualProvider(),
        repoRoute.getModel(),
        projectRoute.getModel());
  }

  private ProjectArchitectureJobStore.Job requireCompatible(
      ProjectArchitectureJobStore.Job job,
      String actorUserId,
      String branch,
      LlmProvider provider,
      String repoModel,
      String projectModel) {
    String normalizedBranch = branch == null || branch.isBlank() ? null : branch.trim();
    if (!java.util.Objects.equals(job.requestedByUserId(), actorUserId)
        || !java.util.Objects.equals(job.requestedBranch(), normalizedBranch)
        || !job.requestedProvider().equals(provider.name())
        || !job.repoArchitectureModel().equals(repoModel)
        || !job.projectArchitectureModel().equals(projectModel)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "A map refresh is already active with a different actor, branch, or exact route");
    }
    return job;
  }

  /**
   * Bounded durable await used only by workflows whose contract requires the newly promoted
   * architecture before continuing. QUEUED/RUNNING are never interpreted as completion.
   */
  public UUID requestAndAwaitArchitectureId(String projectId, String actorUserId) {
    return awaitArchitectureId(request(projectId, actorUserId));
  }

  public UUID requestWithProviderAndAwaitArchitectureId(
      String projectId, String actorUserId, String branch, LlmProvider provider) {
    return awaitArchitectureId(requestWithProvider(projectId, actorUserId, branch, provider));
  }

  UUID awaitArchitectureId(ProjectArchitectureJobStore.Job admitted) {
    long hardStop =
        Math.min(
            admitted.deadlineAt().toEpochMilli() + 5_000L,
            System.currentTimeMillis()
                + Duration.ofSeconds(properties.getDeadlineSeconds() + 5L).toMillis());
    while (System.currentTimeMillis() < hardStop) {
      ProjectArchitectureJobStore.Job current = store.get(admitted.id()).orElseThrow();
      if (current.status() == ProjectArchitectureJobStore.Status.SUCCEEDED
          && current.resultArchitectureId() != null) {
        return current.resultArchitectureId();
      }
      if (current.status().terminal()) {
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Map refresh ended without a promotable architecture: " + current.status());
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE, "Map refresh wait was interrupted");
      }
    }
    throw new ResponseStatusException(
        HttpStatus.GATEWAY_TIMEOUT, "Map refresh did not finish before its durable deadline");
  }

  /**
   * PREPARING resolves one canonical, immutable per-repository source snapshot. Credentials are
   * resolved independently for every linked source and are retained only on this stack frame.
   */
  Prepared prepare(ProjectArchitectureJobStore.Lease lease) {
    ProjectArchitectureJobStore.Job job = lease.job();
    List<SourceRepo> sourceRepos =
        links.findByProjectId(job.projectId()).stream()
            .sorted(Comparator.comparing(link -> link.getRepoId().toString()))
            .map(
                link -> {
                  ProjectRepo repo =
                      repos
                          .findById(link.getRepoId())
                          .orElseThrow(
                              () ->
                                  new ResponseStatusException(
                                      HttpStatus.CONFLICT,
                                      "Linked repository metadata is unavailable"));
                  ScmProvider scm = scmProvider(repo);
                  if (scm != ScmProvider.GITHUB) {
                    throw new ResponseStatusException(
                        HttpStatus.NOT_IMPLEMENTED,
                        "Map refresh currently supports GitHub repository sources only");
                  }
                  String credentialSubject = nonBlank(link.getLinkedBy(), job.requestedByUserId());
                  String secret = credentialResolver.require(credentialSubject, scm).secret();
                  String branch =
                      nonBlank(job.requestedBranch(), nonBlank(repo.getDefaultBranch(), "main"));
                  String fullName = repo.getGithubRepoFullName();
                  String revision =
                      github.getBranchHeadSha("https://github.com/" + fullName, branch, secret);
                  if (revision == null || revision.isBlank()) {
                    throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "Could not resolve a linked repository revision");
                  }
                  return new SourceRepo(
                      repo.getId(),
                      scm.name(),
                      nullToEmpty(repo.getSourceKey()),
                      fullName,
                      branch,
                      revision,
                      credentialSubject);
                })
            .toList();
    if (sourceRepos.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Project has no linked repos — link repos before refreshing map");
    }
    SourceSnapshot snapshot = new SourceSnapshot(1, job.projectId(), sourceRepos);
    String json = canonicalJson(snapshot);
    return new Prepared(snapshot, json, sha256(json));
  }

  ProjectArchitectureJobStore.Job bindPrepared(
      ProjectArchitectureJobStore.Lease lease, Prepared prepared) {
    ProjectArchitectureJobStore.Job job = lease.job();
    var repoReference =
        executionPolicyFactory.forProject(
            job.projectId(),
            "project-architecture-repositories",
            "ARCHITECTURE_JOB_REPOS",
            job.id() + ":repos",
            job.id() + ":repos",
            job.requestedProvider(),
            job.repoArchitectureModel(),
            job.requestedByUserId(),
            new LlmExecutionBudget(700_000L, 80_000L, 780_000L, new BigDecimal("5.00"), 48));
    var projectReference =
        executionPolicyFactory.forProject(
            job.projectId(),
            "project-architecture-combined",
            "ARCHITECTURE_JOB_PROJECT",
            job.id() + ":project",
            job.id() + ":project",
            job.requestedProvider(),
            job.projectArchitectureModel(),
            job.requestedByUserId(),
            new LlmExecutionBudget(300_000L, 40_000L, 340_000L, new BigDecimal("5.00"), 16));
    try {
      if (!store.bindPrepared(
          lease,
          prepared.canonicalJson(),
          prepared.digest(),
          repoReference.contextId(),
          projectReference.contextId())) {
        throw new LeaseLostException();
      }
    } catch (RuntimeException bindFailure) {
      executionContexts.requestCancellation(repoReference.scope(), repoReference.contextId());
      executionContexts.requestCancellation(projectReference.scope(), projectReference.contextId());
      throw bindFailure;
    }
    return store.get(job.id()).orElseThrow();
  }

  void claimContexts(ProjectArchitectureJobStore.Job job, String owner, Duration leaseDuration) {
    LlmExecutionScope scope = LlmExecutionScope.customer(job.orgId());
    if (executionContexts
        .claim(scope, job.repoExecutionContextId(), owner, leaseDuration)
        .isEmpty()) {
      throw new IllegalStateException("Repository architecture execution context is not claimable");
    }
    if (executionContexts
        .claim(scope, job.projectExecutionContextId(), owner, leaseDuration)
        .isEmpty()) {
      executionContexts.requestCancellation(scope, job.repoExecutionContextId());
      executionContexts.acknowledgeCancellation(scope, job.repoExecutionContextId(), owner);
      throw new IllegalStateException("Project architecture execution context is not claimable");
    }
  }

  boolean heartbeatContexts(
      ProjectArchitectureJobStore.Job job, String owner, Duration leaseDuration) {
    LlmExecutionScope scope = LlmExecutionScope.customer(job.orgId());
    return executionContexts.heartbeat(scope, job.repoExecutionContextId(), owner, leaseDuration)
        && executionContexts.heartbeat(
            scope, job.projectExecutionContextId(), owner, leaseDuration);
  }

  void completeContexts(ProjectArchitectureJobStore.Job job, String owner) {
    LlmExecutionScope scope = LlmExecutionScope.customer(job.orgId());
    boolean repo = executionContexts.complete(scope, job.repoExecutionContextId(), owner);
    boolean project = executionContexts.complete(scope, job.projectExecutionContextId(), owner);
    if (!repo || !project) {
      throw new IllegalStateException("Architecture execution contexts could not be completed");
    }
  }

  void failContexts(ProjectArchitectureJobStore.Job job, String owner, String code) {
    if (job.repoExecutionContextId() == null || job.projectExecutionContextId() == null) return;
    LlmExecutionScope scope = LlmExecutionScope.customer(job.orgId());
    var failure =
        new LlmExecutionContextService.Failure(
            code, "Architecture job ended before publication", false, null);
    executionContexts.fail(scope, job.repoExecutionContextId(), owner, failure);
    executionContexts.fail(scope, job.projectExecutionContextId(), owner, failure);
  }

  /** Re-read every snapshotted revision with that link's established current credential. */
  boolean sourceStillCurrent(ProjectArchitectureJobStore.Job job) {
    if (job.sourceSnapshotJson() == null || job.sourceSnapshotDigest() == null) return false;
    SourceSnapshot snapshot = parseSnapshot(job.sourceSnapshotJson());
    if (!sha256(canonicalJson(snapshot)).equals(job.sourceSnapshotDigest())) return false;
    for (SourceRepo source : snapshot.repositories()) {
      ScmProvider scm = ScmProvider.valueOf(source.scmProvider());
      String secret = credentialResolver.require(source.credentialSubjectUserId(), scm).secret();
      String current =
          github.getBranchHeadSha(
              "https://github.com/" + source.repoFullName(), source.branch(), secret);
      if (!source.revision().equals(current)) return false;
    }
    return true;
  }

  Map<UUID, String> resolveCredentials(ProjectArchitectureJobStore.Job job) {
    SourceSnapshot snapshot = parseSnapshot(job.sourceSnapshotJson());
    Map<UUID, String> result = new LinkedHashMap<>();
    for (SourceRepo source : snapshot.repositories()) {
      result.put(
          source.repoId(),
          credentialResolver
              .require(source.credentialSubjectUserId(), ScmProvider.valueOf(source.scmProvider()))
              .secret());
    }
    return Map.copyOf(result);
  }

  SourceSnapshot parseSnapshot(String json) {
    try {
      return objectMapper.readValue(json, SourceSnapshot.class);
    } catch (JsonProcessingException malformed) {
      throw new IllegalStateException(
          "Persisted architecture source snapshot is invalid", malformed);
    }
  }

  String canonicalJson(Object value) {
    try {
      return objectMapper
          .writer()
          .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .writeValueAsString(value);
    } catch (JsonProcessingException impossible) {
      throw new IllegalStateException(
          "Could not canonicalize architecture job evidence", impossible);
    }
  }

  public static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  public ProjectArchitectureJobStore.Job get(String projectId, UUID jobId) {
    return store
        .getScoped(jobId, projectId)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Map refresh job not found"));
  }

  public java.util.Optional<ProjectArchitectureJobStore.Job> latestActive(String projectId) {
    return store.findLatestActive(projectId);
  }

  public java.util.Optional<ProjectArchitectureJobStore.Job> latest(String projectId) {
    return store.findLatest(projectId);
  }

  public boolean cancel(String projectId, UUID jobId) {
    get(projectId, jobId);
    return store.cancel(jobId, projectId);
  }

  public ProjectArchitectureJobStore.Job confirmRetry(
      String projectId, UUID jobId, String confirmingUserId) {
    ProjectArchitectureJobStore.Job current = get(projectId, jobId);
    if (current.status() != ProjectArchitectureJobStore.Status.NEEDS_CONFIRMATION
        || !store.confirmRetry(
            jobId,
            projectId,
            confirmingUserId,
            Duration.ofSeconds(properties.getDeadlineSeconds()))) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Map refresh is not waiting for confirmation from its original requester");
    }
    return get(projectId, jobId);
  }

  private static ScmProvider scmProvider(ProjectRepo repo) {
    return ScmProvider.valueOf(nonBlank(repo.getScmProvider(), "GITHUB").toUpperCase());
  }

  private static String nonBlank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  static final class LeaseLostException extends RuntimeException {}
}
