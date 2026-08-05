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
import com.navneet.executespec.executioncontext.LlmExecutionBudget;
import com.navneet.executespec.executioncontext.LlmExecutionContextService;
import com.navneet.executespec.executioncontext.LlmExecutionOperationCoordinator;
import com.navneet.executespec.executioncontext.LlmExecutionOperationDescriptor;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.repo.ScmCredentialResolver;
import com.navneet.executespec.repo.ScmProvider;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.tool.ExecutionContext;

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
  private final ProjectArchitectureJobAdmission admission;
  private final ProjectRepoLinkRepository links;
  private final ProjectRepoRepository repos;
  private final ScmCredentialResolver credentialResolver;
  private final GitHubRepoService github;
  private final LlmExecutionPolicyFactory executionPolicyFactory;
  private final LlmExecutionContextService executionContexts;
  private final LlmExecutionOperationCoordinator operationCoordinator;
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
    return admission.request(projectId, actorUserId, branchOverride);
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
    return admission.requestExact(projectId, actorUserId, branchOverride, provider, exactModel);
  }

  /** Run-scoped admission with an explicit provider; tier models remain independently resolved. */
  public ProjectArchitectureJobStore.Job requestWithProvider(
      String projectId, String actorUserId, String branchOverride, LlmProvider provider) {
    return admission.requestWithProvider(projectId, actorUserId, branchOverride, provider);
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
    // A confirmation retry receives a fresh deadline. Include it in the durable context identity so
    // the retry cannot inherit a terminal context or operation invocation from the rejected
    // attempt.
    String sourceIdentity =
        architectureContextId(job.id(), prepared.digest(), job.deadlineAt().toEpochMilli());
    var architectureReference =
        executionPolicyFactory.forProject(
            job.projectId(),
            "project-architecture",
            "ARCHITECTURE_JOB",
            sourceIdentity,
            sourceIdentity,
            job.requestedProvider(),
            job.projectArchitectureModel(),
            job.requestedByUserId(),
            new LlmExecutionBudget(1_000_000L, 120_000L, 1_120_000L, new BigDecimal("10.00"), 64));
    try {
      if (!store.bindPrepared(
          lease,
          prepared.canonicalJson(),
          prepared.digest(),
          architectureReference.contextId(),
          architectureReference.contextId())) {
        throw new LeaseLostException();
      }
    } catch (RuntimeException bindFailure) {
      executionContexts.requestCancellation(
          architectureReference.scope(), architectureReference.contextId());
      throw bindFailure;
    }
    return store.get(job.id()).orElseThrow();
  }

  /**
   * Builds the durable source/correlation identity for one architecture-job attempt.
   *
   * <p>The raw job UUID + 64-character source digest + deadline epoch exceeds the execution
   * context's {@code source_id}/{@code correlation_id VARCHAR(96)} contract. Hash the complete
   * tuple into a 128-bit key: source changes and confirmation retries remain distinct without
   * duplicating their full evidence in constrained identity columns.
   */
  static String architectureContextId(UUID jobId, String sourceDigest, long deadlineEpochMillis) {
    String identityEvidence =
        java.util.Objects.requireNonNull(jobId, "jobId")
            + "\u001f"
            + java.util.Objects.requireNonNull(sourceDigest, "sourceDigest")
            + "\u001f"
            + deadlineEpochMillis;
    return "architecture-job:" + sha256(identityEvidence).substring(0, 32);
  }

  void claimContexts(ProjectArchitectureJobStore.Job job, String owner, Duration leaseDuration) {
    LlmExecutionScope scope = LlmExecutionScope.customer(job.orgId());
    if (executionContexts
        .claim(scope, job.repoExecutionContextId(), owner, leaseDuration)
        .isEmpty()) {
      throw new IllegalStateException("Repository architecture execution context is not claimable");
    }
    if (sameContext(job)) return;
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
    boolean repo =
        executionContexts.heartbeat(scope, job.repoExecutionContextId(), owner, leaseDuration);
    return repo
        && (sameContext(job)
            || executionContexts.heartbeat(
                scope, job.projectExecutionContextId(), owner, leaseDuration));
  }

  void completeContexts(ProjectArchitectureJobStore.Job job, String owner) {
    LlmExecutionScope scope = LlmExecutionScope.customer(job.orgId());
    boolean repo = executionContexts.complete(scope, job.repoExecutionContextId(), owner);
    boolean project =
        sameContext(job)
            || executionContexts.complete(scope, job.projectExecutionContextId(), owner);
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
    if (!sameContext(job)) {
      executionContexts.fail(scope, job.projectExecutionContextId(), owner, failure);
    }
  }

  java.util.Optional<LlmExecutionOperationCoordinator.Lease> claimOperation(
      ProjectArchitectureJobStore.Job job, String owner, Duration leaseDuration) {
    return operationCoordinator.claimOrResume(
        operationReference(job), operationDescriptor(job), owner, leaseDuration);
  }

  ExecutionContext bindOperationContext(
      ProjectArchitectureJobStore.Job job, LlmExecutionOperationCoordinator.Lease operationLease) {
    ExecutionContext base =
        new ExecutionContext(
            "project-architecture-" + job.id(),
            "architecture-job",
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir", "/tmp"))
                .toAbsolutePath()
                .normalize(),
            operationReference(job));
    return operationCoordinator.bind(base, operationLease);
  }

  boolean heartbeatOperation(
      LlmExecutionOperationCoordinator.Lease operationLease, Duration leaseDuration) {
    return operationCoordinator.heartbeat(operationLease, leaseDuration);
  }

  void completeOperation(LlmExecutionOperationCoordinator.Lease operationLease) {
    if (!operationCoordinator.complete(operationLease)) {
      throw new IllegalStateException("Architecture logical operation could not be completed");
    }
  }

  void terminateOperation(
      LlmExecutionOperationCoordinator.Lease operationLease, String code, String message) {
    if (operationLease == null) return;
    if (operationCoordinator.acknowledgeCancellation(operationLease)) return;
    if (!operationCoordinator.fail(operationLease, code, message, false, null)) {
      throw new IllegalStateException("Architecture logical operation could not be terminalized");
    }
  }

  private LlmExecutionReference operationReference(ProjectArchitectureJobStore.Job job) {
    return new LlmExecutionReference(
        job.repoExecutionContextId(), LlmExecutionScope.customer(job.orgId()));
  }

  private LlmExecutionOperationDescriptor operationDescriptor(ProjectArchitectureJobStore.Job job) {
    String digest = requirePrepared(job.sourceSnapshotDigest(), "source snapshot digest");
    String operationKey = "project-architecture:" + job.id() + ":" + digest.substring(0, 16);
    String immutableIdentity =
        String.join(
            "|",
            job.id().toString(),
            job.projectId(),
            digest,
            job.deadlineAt().toString(),
            job.requestedProvider(),
            job.repoArchitectureModel(),
            job.projectArchitectureModel());
    // One provider-bearing invocation per explicit architecture-job attempt. An expired/uncertain
    // invocation is never auto-replayed; confirmRetry creates a fresh context identity via
    // deadline.
    return LlmExecutionOperationDescriptor.of(
        operationKey, "PROJECT_ARCHITECTURE", immutableIdentity, 1);
  }

  private static String requirePrepared(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Architecture job missing " + field);
    }
    return value;
  }

  private static boolean sameContext(ProjectArchitectureJobStore.Job job) {
    return java.util.Objects.equals(job.repoExecutionContextId(), job.projectExecutionContextId());
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
    ProjectArchitectureJobStore.Job current = get(projectId, jobId);
    boolean cancelled = store.cancel(jobId, projectId);
    if (cancelled
        && current.repoExecutionContextId() != null
        && current.sourceSnapshotDigest() != null) {
      operationCoordinator.requestCancellation(
          operationReference(current), operationDescriptor(current).operationKey());
    }
    return cancelled;
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
