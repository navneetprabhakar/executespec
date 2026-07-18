package com.navneet.executespec.codereview;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.codereview.entity.CodeReviewIssue;
import com.navneet.executespec.codereview.entity.CodeReviewScan;
import com.navneet.executespec.codereview.repository.CodeReviewIssueRepository;
import com.navneet.executespec.codereview.repository.CodeReviewScanRepository;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.harness.WorkspaceInitService;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.reviewjob.ReviewBackgroundJobStore;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ProjectProviderResolver;
import com.navneet.executespec.router.RunModelOverrides;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrator for the whole-repo Code Review Agent.
 *
 * <p>Lifecycle (with SSE events on channel {@code code-review-{scanId}}):
 *
 * <pre>
 *   QUEUED → CLONING → STATIC_RUNNING → LLM_RUNNING → COMPLETE / FAILED
 * </pre>
 *
 * <ol>
 *   <li>Tier-gate: caller must be PRO / TEAM / MAX / ENTERPRISE; FREE blocked.
 *   <li>Quota-gate: per-tier monthly cap (PRO=4, TEAM/MAX/ENTERPRISE=unlimited).
 *   <li>Idempotency: if a recent COMPLETE scan exists for the same (project, repo, sha), return it
 *       directly instead of re-scanning.
 *   <li>Persist a row in {@code QUEUED}; schedule the actual work on the dispatch pool.
 *   <li>Dispatch path: clone → semgrep → LLM augmentation → persist issues + markdown report.
 * </ol>
 *
 * <p>Like {@link com.navneet.executespec.prreview.PrReviewService} this is async-with-SSE so the
 * controller returns 202 immediately. Pool drains on {@code @PreDestroy}.
 */
@Service
@Slf4j
public class CodeReviewService {

  /**
   * Cap on per-file content sent to the agent (chars). Keeps the LLM payload bounded; we sample the
   * most-risky files (controllers, configs, recent changes) up to this size each.
   */
  private static final int MAX_BYTES_PER_FILE = 12_000;

  /** Cap on number of files included in the agent prompt. */
  private static final int MAX_FILES_IN_PROMPT = 20;

  /** Total workspace traversal cap — files above this don't even count toward sampling. */
  private static final int MAX_FILES_SCANNED = 5_000;

  private final CodeReviewScanRepository scanRepo;
  private final CodeReviewIssueRepository issueRepo;
  private final SemgrepRunner semgrep;
  private final CodeReviewAgent agent;
  private final WorkspaceInitService workspaceInitService;
  private final RunCostTracker costTracker;
  private final SseBroadcaster broadcaster;
  private final ProjectRepoRepository projectRepoRepo;
  private final GitHubRepoService github;
  private final TierConfigRepository tierConfigRepo;
  private final ProjectProviderResolver projectProviderResolver;
  private final ReviewBackgroundJobStore reviewJobStore;
  private final LlmExecutionPolicyFactory executionPolicyFactory;
  private final ModelResolver modelResolver;
  private final ProjectRepository projectRepository;
  private final AppUserRepository appUserRepository;
  private final ObjectMapper objectMapper;

  @Autowired
  public CodeReviewService(
      CodeReviewScanRepository scanRepo,
      CodeReviewIssueRepository issueRepo,
      SemgrepRunner semgrep,
      CodeReviewAgent agent,
      WorkspaceInitService workspaceInitService,
      RunCostTracker costTracker,
      SseBroadcaster broadcaster,
      ProjectRepoRepository projectRepoRepo,
      GitHubRepoService github,
      TierConfigRepository tierConfigRepo,
      ProjectProviderResolver projectProviderResolver,
      ReviewBackgroundJobStore reviewJobStore,
      LlmExecutionPolicyFactory executionPolicyFactory,
      ModelResolver modelResolver,
      ProjectRepository projectRepository,
      AppUserRepository appUserRepository,
      ObjectMapper objectMapper) {
    this.scanRepo = scanRepo;
    this.issueRepo = issueRepo;
    this.semgrep = semgrep;
    this.agent = agent;
    this.workspaceInitService = workspaceInitService;
    this.costTracker = costTracker;
    this.broadcaster = broadcaster;
    this.projectRepoRepo = projectRepoRepo;
    this.github = github;
    this.tierConfigRepo = tierConfigRepo;
    this.projectProviderResolver = projectProviderResolver;
    this.reviewJobStore = reviewJobStore;
    this.executionPolicyFactory = executionPolicyFactory;
    this.modelResolver = modelResolver;
    this.projectRepository = projectRepository;
    this.appUserRepository = appUserRepository;
    this.objectMapper = objectMapper;
  }

  /**
   * Source-compatible constructor retained for focused tests that exercise only tier/provider
   * helpers.
   */
  CodeReviewService(
      CodeReviewScanRepository scanRepo,
      CodeReviewIssueRepository issueRepo,
      SemgrepRunner semgrep,
      CodeReviewAgent agent,
      WorkspaceInitService workspaceInitService,
      RunCostTracker costTracker,
      SseBroadcaster broadcaster,
      ProjectRepoRepository projectRepoRepo,
      GitHubRepoService github,
      TierConfigRepository tierConfigRepo,
      ProjectProviderResolver projectProviderResolver) {
    this(
        scanRepo,
        issueRepo,
        semgrep,
        agent,
        workspaceInitService,
        costTracker,
        broadcaster,
        projectRepoRepo,
        github,
        tierConfigRepo,
        projectProviderResolver,
        null,
        null,
        null,
        null,
        null,
        new ObjectMapper());
  }

  /** No service-owned queue remains; retained as a no-op for source compatibility. */
  void shutdown() {}

  public void assertTierAndQuota(AppUser user) {
    if (user == null) throw new TierBlockedException("authentication required");
    String tier = user.getTier();
    if (tier == null || "FREE".equalsIgnoreCase(tier)) {
      throw new TierBlockedException(
          "Code review is a paid feature — upgrade to PRO or above to enable.");
    }
    TierConfig cfg = tierConfigRepo.findById(tier).orElse(null);
    if (cfg == null || !Boolean.TRUE.equals(cfg.getCodeReviewEnabled())) {
      throw new TierBlockedException(
          "Code review not enabled for tier " + tier + " — contact support if this is wrong.");
    }
    int quota = cfg.getCodeReviewMonthlyQuota() == null ? 0 : cfg.getCodeReviewMonthlyQuota();
    if (quota >= 0) {
      Instant monthStart =
          Instant.now()
              .atZone(ZoneOffset.UTC)
              .toLocalDate()
              .withDayOfMonth(1)
              .atStartOfDay()
              .toInstant(ZoneOffset.UTC);
      long usedThisMonth = scanRepo.countByUserSince(user.getId(), monthStart);
      if (usedThisMonth >= quota) {
        throw new QuotaExhaustedException(
            "Monthly code-review quota exhausted ("
                + usedThisMonth
                + "/"
                + quota
                + "). Resets on the 1st UTC.");
      }
    }
  }

  /** Submit a code-review scan. Returns the QUEUED row immediately. */
  @Transactional
  public CodeReviewScan submitScan(
      String projectId, UUID repoId, String repoFullName, AppUser user) {
    return submitScanInternal(projectId, repoId, repoFullName, user, null, null, null);
  }

  /**
   * Project Chat path: preserve the consumed confirmation's exact provider/model asynchronously.
   */
  @Transactional
  public CodeReviewScan submitConfirmedScan(
      String projectId,
      UUID repoId,
      String repoFullName,
      AppUser user,
      LlmProvider confirmedProvider,
      String confirmedModel,
      LlmExecutionReference confirmedActionReference) {
    if (confirmedProvider == null || confirmedModel == null || confirmedModel.isBlank()) {
      throw new IllegalArgumentException("confirmed provider and model are required");
    }
    if (confirmedActionReference == null) {
      throw new IllegalArgumentException("confirmed action execution reference is required");
    }
    return submitScanInternal(
        projectId,
        repoId,
        repoFullName,
        user,
        confirmedProvider,
        confirmedModel.trim(),
        confirmedActionReference);
  }

  private CodeReviewScan submitScanInternal(
      String projectId,
      UUID repoId,
      String repoFullName,
      AppUser user,
      LlmProvider confirmedProvider,
      String confirmedModel,
      LlmExecutionReference confirmedActionReference) {
    if (projectId == null || repoId == null || repoFullName == null) {
      throw new IllegalArgumentException("projectId, repoId, and repoFullName are required");
    }
    String pat = user == null ? null : user.getGithubPat();
    // Resolve default branch + head sha for an idempotency-safe scan key.
    String repoUrl = "https://github.com/" + repoFullName + ".git";
    String defaultBranch = github.getDefaultBranch(repoUrl, pat);
    if (defaultBranch == null || defaultBranch.isBlank()) defaultBranch = "main";
    String sha = github.getBranchHeadSha(repoUrl, defaultBranch, pat);
    if (sha == null || sha.isBlank()) {
      // We can still scan against whatever HEAD ends up being; record an "unknown" marker so
      // the idempotency key doesn't collide with a different commit.
      sha = "unknown-" + Instant.now().toEpochMilli();
    }
    final String resolvedSha = sha;

    // V299 idempotency covers queued/running/terminal work for the immutable repo/SHA tuple.
    Optional<CodeReviewScan> existing =
        scanRepo.findByProjectIdAndRepoIdAndScannedSha(projectId, repoId, resolvedSha);
    if (existing.isPresent()) {
      log.info(
          "CodeReview idempotent re-use scan={} project={} repo={} sha={}",
          existing.get().getId(),
          projectId,
          repoFullName,
          resolvedSha);
      return existing.get();
    }

    CodeReviewScan row =
        CodeReviewScan.builder()
            .projectId(projectId)
            .repoId(repoId)
            .repoFullName(repoFullName)
            .scannedSha(resolvedSha)
            .status("QUEUED")
            .triggerSource("MANUAL")
            .startedAt(Instant.now())
            .createdByUserId(user == null ? null : user.getId())
            .build();
    CodeReviewScan saved = scanRepo.save(row);

    String channel = "code-review-" + saved.getId();
    broadcaster.emit(channel, RunEvent.runStatus(channel, "QUEUED"));

    String actorUserId = user == null ? null : user.getId();
    var project = projectRepository.findById(projectId).orElseThrow();
    LlmProvider provider =
        confirmedProvider == null ? projectProviderResolver.resolve(projectId) : confirmedProvider;
    String model =
        confirmedModel == null
            ? modelResolver.resolve("CodeReviewAgent", provider).getModel()
            : confirmedModel;
    LlmExecutionReference executionReference =
        confirmedActionReference == null
            ? executionPolicyFactory.forProject(
                projectId,
                "code-review",
                "CODE_REVIEW",
                saved.getId().toString(),
                saved.getId().toString(),
                provider.name(),
                model,
                actorUserId)
            : confirmedActionReference;
    try {
      String payload =
          objectMapper.writeValueAsString(Map.of("repoFullName", repoFullName, "sha", resolvedSha));
      reviewJobStore.createOrGet(
          new ReviewBackgroundJobStore.Job(
              UUID.randomUUID(),
              ReviewBackgroundJobStore.Type.CODE_REVIEW,
              saved.getId(),
              projectId,
              project.getOrgId(),
              repoId,
              actorUserId,
              "code-review:" + projectId + ":" + repoId + ":" + resolvedSha,
              provider.name(),
              model,
              executionReference.contextId(),
              executionReference.scope().mode().name(),
              executionReference.scope().orgId(),
              payload,
              null,
              null,
              ReviewBackgroundJobStore.Status.QUEUED,
              0,
              3,
              Instant.now(),
              null,
              0,
              null,
              null,
              null,
              null,
              null,
              Instant.now(),
              null,
              null));
    } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
      throw new IllegalStateException("could not encode code review job", impossible);
    }
    return saved;
  }

  /** Executes one V299 job using only its durable actor, route and target snapshots. */
  public DurableCodeReviewResult runDurableJob(
      ReviewBackgroundJobStore.Lease lease, String repoFullName, String sha, Runnable checkpoint) {
    ReviewBackgroundJobStore.Job job = lease.job();
    AppUser actor =
        appUserRepository
            .findById(job.requestedByUserId())
            .orElseThrow(() -> new IllegalStateException("review actor no longer exists"));
    var scope =
        new com.navneet.executespec.executioncontext.LlmExecutionScope(
            com.navneet.executespec.executioncontext.LlmExecutionScope.ScopeMode.valueOf(
                job.executionScopeMode()),
            job.executionScopeOrgId());
    var reference = new LlmExecutionReference(job.executionContextId(), scope);
    LlmProvider provider = LlmProvider.valueOf(job.provider());
    java.util.concurrent.atomic.AtomicReference<DurableCodeReviewResult> result =
        new java.util.concurrent.atomic.AtomicReference<>();
    RunModelOverrides.runWith(
        RunModelOverrides.withRunPin(Map.of(), provider, job.model()),
        () ->
            result.set(
                runScan(
                    lease,
                    repoFullName,
                    sha,
                    actor.getGithubPat(),
                    provider,
                    job.model(),
                    actor.getId(),
                    reference,
                    checkpoint)));
    return result.get();
  }

  DurableCodeReviewResult runScan(
      ReviewBackgroundJobStore.Lease lease,
      String repoFullName,
      String sha,
      String pat,
      LlmProvider confirmedProvider,
      String confirmedModel,
      String actorUserId,
      LlmExecutionReference confirmedActionReference,
      Runnable checkpoint) {
    UUID scanId = lease.job().subjectId();
    String projectId = lease.job().projectId();
    UUID repoId = lease.job().repoId();
    String channel = "code-review-" + scanId;
    long start = System.currentTimeMillis();
    checkpoint.run();

    // 1. CLONING — provision a workspace at workspace/{scan-{scanId}} and clone the repo.
    requireStatusUpdate(reviewJobStore.updateCodeReviewStatus(lease, "CLONING"));
    broadcaster.emit(channel, RunEvent.runStatus(channel, "CLONING"));
    broadcaster.emit(
        channel, RunEvent.log(channel, "scan", "Cloning " + repoFullName + " @ " + shortSha(sha)));

    String workspaceRunId = "scan-" + scanId;
    Path workspaceRoot;
    try {
      String cloneUrl = resolveCloneUrl(repoId, repoFullName);
      workspaceInitService.cloneFromRepo(workspaceRunId, cloneUrl, pat);
      workspaceRoot = workspaceInitService.runWorkspacePath(workspaceRunId);
      checkpoint.run();
    } catch (Exception e) {
      log.warn("CodeReview clone failed scan={}: {}", scanId, e.getMessage());
      broadcaster.emit(channel, RunEvent.log(channel, "scan", "❌ Clone failed: " + e.getMessage()));
      throw new IllegalStateException("clone failed", e);
    }

    // 2. STATIC_RUNNING — semgrep
    requireStatusUpdate(reviewJobStore.updateCodeReviewStatus(lease, "STATIC_RUNNING"));
    broadcaster.emit(channel, RunEvent.runStatus(channel, "STATIC_RUNNING"));
    broadcaster.emit(channel, RunEvent.log(channel, "scan", "Running semgrep static analysis…"));

    SemgrepRunner.ScanResult staticResult = semgrep.scan(workspaceRoot);
    checkpoint.run();
    int staticCount = staticResult.findings().size();
    broadcaster.emit(
        channel,
        RunEvent.log(
            channel,
            "scan",
            staticResult.available()
                ? ("✓ Semgrep: "
                    + staticCount
                    + " findings across "
                    + staticResult.totalFilesScanned()
                    + " files in "
                    + staticResult.durationMs()
                    + "ms")
                : ("⚠ Semgrep unavailable ("
                    + staticResult.unavailableReason()
                    + ") — continuing with LLM-only pass")));

    // 3. LLM_RUNNING — augmentation pass
    requireStatusUpdate(reviewJobStore.updateCodeReviewStatus(lease, "LLM_RUNNING"));
    broadcaster.emit(channel, RunEvent.runStatus(channel, "LLM_RUNNING"));
    broadcaster.emit(
        channel,
        RunEvent.log(
            channel,
            "scan",
            "LLM augmentation pass over " + staticCount + " static findings + repo sample…"));

    SampleResult samples = sampleFiles(workspaceRoot, staticResult.findings());
    Set<String> languages = samples.languages();
    int totalLoc = samples.totalLoc();
    int totalFiles = samples.totalFiles();

    List<CodeReviewAgent.StaticFinding> staticForAgent =
        staticResult.findings().stream()
            .map(
                f ->
                    new CodeReviewAgent.StaticFinding(
                        f.ruleId(),
                        f.severity(),
                        f.category(),
                        f.filePath(),
                        f.lineStart(),
                        f.lineEnd(),
                        f.message(),
                        f.snippet()))
            .toList();

    CodeReviewAgent.AgentRequest agentReq =
        CodeReviewAgent.AgentRequest.builder()
            .scanId(scanId.toString())
            .projectId(projectId)
            .repoFullName(repoFullName)
            .scannedSha(sha)
            .languages(new ArrayList<>(languages))
            .fileCount(totalFiles)
            .totalLoc(totalLoc)
            .staticFindings(staticForAgent)
            .fileSamples(samples.samples())
            .provider(
                confirmedProvider == null
                    ? resolveProvider(projectId)
                    : confirmedProvider.name().toLowerCase())
            .model(confirmedModel)
            .actorUserId(actorUserId)
            .executionReference(confirmedActionReference)
            .build();

    CodeReviewAgent.AgentResult agentResult;
    try {
      agentResult = agent.review(agentReq);
      checkpoint.run();
    } catch (Exception e) {
      log.warn("CodeReview agent failed scan={}: {}", scanId, e.getMessage());
      broadcaster.emit(
          channel, RunEvent.log(channel, "scan", "❌ LLM pass failed: " + e.getMessage()));
      throw new IllegalStateException("agent failed", e);
    }

    return new DurableCodeReviewResult(
        staticResult, agentResult, totalFiles, totalLoc, System.currentTimeMillis() - start);
  }

  /** Locks the V299 fence and publishes result + terminal job state in one transaction. */
  @Transactional
  public void publishDurableResult(
      ReviewBackgroundJobStore.Lease lease, DurableCodeReviewResult result) {
    reviewJobStore.requireLiveLeaseForUpdate(lease);
    CodeReviewSummary summary =
        completeScan(
            lease.job().subjectId(),
            result.staticResult(),
            result.agentResult(),
            result.totalFiles(),
            result.totalLoc(),
            result.durationMs());
    if (!reviewJobStore.complete(lease)) throw new ReviewBackgroundJobStore.LeaseFencedException();
    String channel = "code-review-" + lease.job().subjectId();
    broadcaster.emit(
        channel,
        RunEvent.log(
            channel,
            "scan",
            "✅ Done. "
                + summary.totalIssues()
                + " issues persisted ("
                + summary.criticalCount()
                + " CRITICAL, "
                + summary.majorCount()
                + " MAJOR), "
                + summary.droppedCount()
                + " static findings dropped as false positives."));
    broadcaster.emit(channel, RunEvent.complete(channel, "COMPLETE", "scan complete"));
  }

  private static void requireStatusUpdate(boolean updated) {
    if (!updated) throw new ReviewBackgroundJobStore.LeaseFencedException();
  }

  public record DurableCodeReviewResult(
      SemgrepRunner.ScanResult staticResult,
      CodeReviewAgent.AgentResult agentResult,
      int totalFiles,
      int totalLoc,
      long durationMs) {}

  /** Resolve the linked project's configured provider; never hardcode a review-only provider. */
  String resolveProvider(String projectId) {
    return projectProviderResolver.resolve(projectId).name().toLowerCase(java.util.Locale.ROOT);
  }

  /** Dedicated transactional method so the persistence step is atomic. */
  @Transactional
  CodeReviewSummary completeScan(
      UUID scanId,
      SemgrepRunner.ScanResult staticResult,
      CodeReviewAgent.AgentResult agentResult,
      int totalFiles,
      int totalLoc,
      long durationMs) {
    CodeReviewScan row = scanRepo.findById(scanId).orElse(null);
    if (row == null) {
      log.warn("CodeReview row {} vanished mid-scan", scanId);
      return new CodeReviewSummary(0, 0, 0, 0, 0);
    }
    row.setStatus("COMPLETE");
    row.setTotalFilesScanned(totalFiles);
    row.setTotalLoc(totalLoc);
    row.setStaticFindingsCount(staticResult.findings().size());
    row.setLlmFindingsCount(agentResult.issues().size());
    row.setLlmFilteredOutCount(agentResult.droppedStaticFindings().size());
    row.setDurationMs(durationMs);
    row.setFinishedAt(Instant.now());
    row.setFailureReason(null);

    // Cost attribution
    var snap = costTracker.snapshot("code-review-" + scanId);
    row.setCostUsd(snap.costUsd());
    row.setTotalInputTokens((long) snap.inputTokens());
    row.setTotalOutputTokens((long) snap.outputTokens());

    // Persist issues — drop-and-replace so re-runs are clean.
    issueRepo.deleteAll(issueRepo.findByScanIdOrderBySeverityAscFilePathAscLineStartAsc(scanId));

    Set<String> droppedKeys =
        agentResult.droppedStaticFindings().stream()
            .map(d -> d.ruleId() + "|" + d.filePath() + "|" + d.lineStart())
            .collect(Collectors.toSet());

    List<CodeReviewIssue> persisted = new ArrayList<>();
    int critical = 0;
    int major = 0;

    // Issues from the agent's structured output (already triaged/rewritten static + LLM-only)
    for (CodeReviewAgent.AgentIssue i : agentResult.issues()) {
      String fingerprint =
          fingerprint(i.ruleId(), i.filePath(), i.lineStart(), i.message(), i.severity());
      persisted.add(
          CodeReviewIssue.builder()
              .scanId(scanId)
              .fingerprint(fingerprint)
              .severity(i.severity())
              .category(i.category())
              .source(i.source())
              .ruleId(i.ruleId())
              .filePath(i.filePath())
              .lineStart(i.lineStart())
              .lineEnd(i.lineEnd())
              .message(i.message())
              .suggestedFix(i.suggestedFix())
              .status("OPEN")
              .build());
      if ("CRITICAL".equals(i.severity())) critical++;
      else if ("MAJOR".equals(i.severity())) major++;
    }

    // Persist any STATIC findings the agent neither dropped nor rewrote — keep them as
    // first-class STATIC entries so the dashboard accurately reflects what semgrep saw.
    Set<String> agentRuleKeys =
        agentResult.issues().stream()
            .map(i -> i.ruleId() + "|" + i.filePath() + "|" + i.lineStart())
            .collect(Collectors.toSet());
    for (SemgrepRunner.Finding f : staticResult.findings()) {
      String key = f.ruleId() + "|" + f.filePath() + "|" + f.lineStart();
      if (droppedKeys.contains(key) || agentRuleKeys.contains(key)) continue;
      String fingerprint =
          fingerprint(f.ruleId(), f.filePath(), f.lineStart(), f.message(), f.severity());
      persisted.add(
          CodeReviewIssue.builder()
              .scanId(scanId)
              .fingerprint(fingerprint)
              .severity(f.severity())
              .category(f.category())
              .source("STATIC")
              .ruleId(f.ruleId())
              .filePath(f.filePath())
              .lineStart(f.lineStart())
              .lineEnd(f.lineEnd())
              .message(f.message())
              .status("OPEN")
              .build());
      if ("CRITICAL".equals(f.severity())) critical++;
      else if ("MAJOR".equals(f.severity())) major++;
    }
    issueRepo.saveAll(persisted);

    // Render markdown report — chat surface + download endpoint use this verbatim.
    row.setReportMarkdown(
        CodeReviewMarkdownRenderer.render(row, agentResult, persisted, droppedKeys.size()));
    scanRepo.save(row);

    // Free cost-tracker per-scan bucket.
    costTracker.finalizeRun("code-review-" + scanId);

    return new CodeReviewSummary(
        persisted.size(), critical, major, droppedKeys.size(), staticResult.findings().size());
  }

  @Transactional
  void setStatus(UUID scanId, String status) {
    scanRepo
        .findById(scanId)
        .ifPresent(
            r -> {
              r.setStatus(status);
              scanRepo.save(r);
            });
  }

  public boolean requestCancellation(UUID scanId) {
    var job = reviewJobStore.requestCancellation(ReviewBackgroundJobStore.Type.CODE_REVIEW, scanId);
    if (job.isEmpty()
        || job.get().status().terminal()
            && job.get().status() != ReviewBackgroundJobStore.Status.CANCELLED) {
      return false;
    }
    return true;
  }

  @Transactional
  public boolean retryScan(UUID scanId) {
    var job = reviewJobStore.retry(ReviewBackgroundJobStore.Type.CODE_REVIEW, scanId);
    if (job.isEmpty() || job.get().status() != ReviewBackgroundJobStore.Status.QUEUED) return false;
    scanRepo
        .findById(scanId)
        .ifPresent(
            row -> {
              row.setStatus("QUEUED");
              row.setFinishedAt(null);
              row.setFailureReason(null);
              scanRepo.save(row);
            });
    return true;
  }

  // File sampling for the LLM prompt

  /**
   * Walk the workspace, prioritise risky / interesting files, return at most {@link
   * #MAX_FILES_IN_PROMPT} excerpts (each capped at {@link #MAX_BYTES_PER_FILE} chars). Counts
   * languages + LOC across the whole tree (capped at {@link #MAX_FILES_SCANNED}).
   */
  SampleResult sampleFiles(Path root, List<SemgrepRunner.Finding> staticFindings) {
    Set<String> languages = new HashSet<>();
    int totalLoc = 0;
    int totalFiles = 0;
    List<FileMeta> all = new ArrayList<>();
    if (!Files.isDirectory(root)) {
      return new SampleResult(List.of(), languages, totalLoc, totalFiles);
    }
    try (Stream<Path> stream = Files.walk(root)) {
      var iter = stream.iterator();
      while (iter.hasNext() && totalFiles < MAX_FILES_SCANNED) {
        Path p = iter.next();
        if (Files.isDirectory(p)) continue;
        Path rel = root.relativize(p);
        String relStr = rel.toString().replace('\\', '/');
        if (shouldSkip(relStr)) continue;
        String ext = extensionOf(relStr);
        String lang = LANG_BY_EXT.getOrDefault(ext, "");
        if (!lang.isBlank()) languages.add(lang);
        long size;
        try {
          size = Files.size(p);
        } catch (IOException e) {
          continue;
        }
        if (size > 1_000_000) continue; // skip huge files (>1MB)
        totalFiles++;
        // Quick LOC estimate from byte-count if extension is text-like.
        int loc = 0;
        if (!lang.isBlank()) {
          try {
            byte[] bytes = Files.readAllBytes(p);
            String content = new String(bytes, StandardCharsets.UTF_8);
            loc = (int) content.lines().count();
            totalLoc += loc;
            all.add(new FileMeta(relStr, lang, content, scoreFile(relStr, content)));
          } catch (IOException e) {
            // skip unreadable
          }
        }
      }
    } catch (IOException e) {
      log.warn("CodeReview file walk failed: {}", e.getMessage());
    }

    // Boost score for files referenced by static findings — those are likely the hot spots.
    Set<String> hotPaths =
        staticFindings.stream().map(SemgrepRunner.Finding::filePath).collect(Collectors.toSet());
    for (FileMeta m : all) {
      if (hotPaths.contains(m.path())) m.bumpScore(50);
    }

    all.sort(Comparator.comparingInt(FileMeta::score).reversed());
    List<CodeReviewAgent.FileSample> picked = new ArrayList<>();
    for (FileMeta m : all) {
      if (picked.size() >= MAX_FILES_IN_PROMPT) break;
      String content = m.content();
      if (content.length() > MAX_BYTES_PER_FILE) {
        content = content.substring(0, MAX_BYTES_PER_FILE) + "\n\n…(truncated)\n";
      }
      picked.add(new CodeReviewAgent.FileSample(m.path(), m.lang(), content));
    }
    return new SampleResult(picked, languages, totalLoc, totalFiles);
  }

  /** Skip rule for files we don't want walking into the prompt OR the LOC count. */
  static boolean shouldSkip(String relPath) {
    if (relPath.startsWith(".git/") || relPath.equals(".git")) return true;
    if (relPath.startsWith("node_modules/")) return true;
    if (relPath.startsWith("target/")
        || relPath.startsWith("build/")
        || relPath.startsWith("dist/")) return true;
    if (relPath.startsWith(".gradle/") || relPath.startsWith(".mvn/")) return true;
    if (relPath.startsWith(".idea/") || relPath.startsWith(".vscode/")) return true;
    if (relPath.endsWith(".lock") || relPath.endsWith(".min.js") || relPath.endsWith(".min.css"))
      return true;
    if (relPath.endsWith(".png")
        || relPath.endsWith(".jpg")
        || relPath.endsWith(".jpeg")
        || relPath.endsWith(".gif")
        || relPath.endsWith(".pdf")
        || relPath.endsWith(".zip")
        || relPath.endsWith(".tar")
        || relPath.endsWith(".gz")) return true;
    return false;
  }

  /**
   * Heuristic risk score for prioritising files into the prompt. Higher = more important.
   * Controllers / auth / db / config files score highest; tests + fixtures lowest.
   */
  static int scoreFile(String path, String content) {
    int s = 0;
    String lower = path.toLowerCase();
    if (lower.contains("/controller") || lower.contains("controllers/")) s += 30;
    if (lower.contains("/auth/") || lower.contains("/security/")) s += 30;
    if (lower.contains("/service/") || lower.contains("services/")) s += 20;
    if (lower.contains("/repository/") || lower.contains("repositories/")) s += 15;
    if (lower.contains("/api/")) s += 10;
    if (lower.endsWith(".sql")) s += 25;
    if (lower.contains("docker") || lower.contains(".env") || lower.contains("config")) s += 15;
    if (lower.contains("/test/") || lower.endsWith("test.java") || lower.endsWith(".test.ts"))
      s -= 30;
    if (lower.endsWith("readme.md") || lower.endsWith(".md")) s -= 20;
    if (content.length() < 200) s -= 10;
    return s;
  }

  static String extensionOf(String path) {
    int slash = path.lastIndexOf('/');
    int dot = path.lastIndexOf('.');
    if (dot < 0 || dot < slash) return "";
    return path.substring(dot).toLowerCase();
  }

  /** Extension → language label map for lang detection + fence info-string. */
  static final Map<String, String> LANG_BY_EXT =
      Map.ofEntries(
          Map.entry(".java", "java"),
          Map.entry(".kt", "kotlin"),
          Map.entry(".ts", "typescript"),
          Map.entry(".tsx", "typescript"),
          Map.entry(".js", "javascript"),
          Map.entry(".jsx", "javascript"),
          Map.entry(".py", "python"),
          Map.entry(".go", "go"),
          Map.entry(".rs", "rust"),
          Map.entry(".rb", "ruby"),
          Map.entry(".cs", "csharp"),
          Map.entry(".swift", "swift"),
          Map.entry(".php", "php"),
          Map.entry(".sql", "sql"),
          Map.entry(".yml", "yaml"),
          Map.entry(".yaml", "yaml"),
          Map.entry(".json", "json"),
          Map.entry(".sh", "bash"),
          Map.entry(".tf", "terraform"));

  public CodeReviewScan findScan(UUID id) {
    return scanRepo.findById(id).orElse(null);
  }

  public org.springframework.data.domain.Slice<CodeReviewScan> listForProject(
      String projectId, Instant since, org.springframework.data.domain.Pageable pageable) {
    return scanRepo.findByProjectIdAndStartedAtGreaterThanEqualOrderByStartedAtDescIdDesc(
        projectId, since, pageable);
  }

  /**
   * Latest COMPLETE scan only — used by the chat-tool surface so the user never gets a partial /
   * failed report narrated as if it were the canonical answer.
   */
  public Optional<CodeReviewScan> latestForProjectRepo(String projectId, String repoFullName) {
    return scanRepo.findFirstByProjectIdAndRepoFullNameAndStatusOrderByStartedAtDesc(
        projectId, repoFullName, "COMPLETE");
  }

  /**
   * Latest scan regardless of status — used by the dashboard so a FAILED scan with its {@code
   * failureReason} stays visible instead of disappearing back to a "No scan yet" empty state.
   * Surface in the controller's {@code GET /api/projects/{id}/code-reviews/latest} endpoint.
   */
  public Optional<CodeReviewScan> latestForProjectRepoAnyStatus(
      String projectId, String repoFullName) {
    return scanRepo.findFirstByProjectIdAndRepoFullNameOrderByStartedAtDesc(
        projectId, repoFullName);
  }

  public List<CodeReviewIssue> issuesFor(UUID scanId) {
    return issueRepo.findByScanIdOrderBySeverityAscFilePathAscLineStartAsc(scanId);
  }

  public List<CodeReviewIssue> openIssuesFor(UUID scanId) {
    return issueRepo.findByScanIdAndStatusOrderBySeverityAscFilePathAscLineStartAsc(scanId, "OPEN");
  }

  private String resolveCloneUrl(UUID repoId, String repoFullName) {
    ProjectRepo repo =
        projectRepoRepo
            .findById(repoId)
            .orElseThrow(() -> new IllegalStateException("linked repository no longer exists"));
    String canonicalName = repo.getGithubRepoFullName();
    if (canonicalName == null || !canonicalName.equalsIgnoreCase(repoFullName)) {
      throw new IllegalStateException("review repository identity changed after admission");
    }
    return "https://github.com/" + canonicalName + ".git";
  }

  static String shortSha(String sha) {
    if (sha == null) return "(none)";
    return sha.length() <= 7 ? sha : sha.substring(0, 7);
  }

  /** sha256-prefix fingerprint used by the dashboard to dedupe across re-scans. */
  static String fingerprint(
      String ruleId, String filePath, int lineStart, String message, String severity) {
    String key =
        (ruleId == null ? "" : ruleId)
            + "|"
            + (filePath == null ? "" : filePath)
            + "|"
            + lineStart
            + "|"
            + (message == null ? "" : message.length() < 80 ? message : message.substring(0, 80))
            + "|"
            + (severity == null ? "" : severity);
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(key.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : hash) sb.append(String.format("%02x", b));
      return sb.length() > 64 ? sb.substring(0, 64) : sb.toString();
    } catch (Exception e) {
      // SHA-256 always available; fallback for safety only.
      return Integer.toHexString(key.hashCode());
    }
  }

  /** Mutable score holder used during file sampling. */
  static final class FileMeta {
    private final String path;
    private final String lang;
    private final String content;
    private int score;

    FileMeta(String path, String lang, String content, int score) {
      this.path = path;
      this.lang = lang;
      this.content = content;
      this.score = score;
    }

    String path() {
      return path;
    }

    String lang() {
      return lang;
    }

    String content() {
      return content;
    }

    int score() {
      return score;
    }

    void bumpScore(int by) {
      this.score += by;
    }
  }

  record SampleResult(
      List<CodeReviewAgent.FileSample> samples,
      Set<String> languages,
      int totalLoc,
      int totalFiles) {}

  /** Summary of a completed scan — returned by {@link #completeScan} for SSE log lines. */
  public record CodeReviewSummary(
      int totalIssues,
      int criticalCount,
      int majorCount,
      int droppedCount,
      int staticFindingsTotal) {}

  public static class TierBlockedException extends RuntimeException {
    public TierBlockedException(String msg) {
      super(msg);
    }
  }

  public static class QuotaExhaustedException extends RuntimeException {
    public QuotaExhaustedException(String msg) {
      super(msg);
    }
  }
}
