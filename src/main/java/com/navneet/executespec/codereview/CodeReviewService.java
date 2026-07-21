package com.navneet.executespec.codereview;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
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
import com.navneet.executespec.security.GeneratedSecretScanner;
import com.navneet.executespec.security.SafeWorkspacePath;
import com.navneet.executespec.security.SensitiveWorkspacePathPolicy;
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

  /**
   * RR-435 — when a file is a static-finding hot spot we send bounded line windows around each
   * finding anchor instead of the whole {@link #MAX_BYTES_PER_FILE} head, so a large flagged file
   * discloses only the relevant neighbourhood. This is the +/- context radius (in lines) around
   * each anchor; overlapping windows are merged.
   */
  private static final int FINDING_CONTEXT_LINES = 40;

  /** Total workspace traversal cap — files above this don't even count toward sampling. */
  private static final int MAX_FILES_SCANNED = 5_000;

  /** Largest single file the sampler will open at all. */
  private static final long MAX_FILE_SIZE_BYTES = 1_000_000;

  /**
   * RR-433 — cumulative byte budget for the sampling walk, checked against {@code
   * BasicFileAttributes.size()} BEFORE each read. 5,000 files × 1 MB was a 5 GB read budget with no
   * ceiling, and every decoded file was retained in a list until after the walk. Reads are now both
   * budgeted and streamed, and retention is bounded by the top-K heap rather than by the walk.
   */
  private static final long MAX_TOTAL_READ_BYTES = 256L * 1024 * 1024;

  /** RR-433 — wall-clock budget for the sampling walk on a pathologically large repository. */
  private static final long MAX_SAMPLE_DURATION_MILLIS = 120_000;

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

  /**
   * RR-435 — deterministic credential redactor applied to every byte the sampler retains and to
   * every static-analysis snippet, before either can reach the review model. Reused rather than
   * reimplemented so this boundary cannot drift from the platform's credential pattern set.
   */
  private final GeneratedSecretScanner secretScanner;

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
      ObjectMapper objectMapper,
      GeneratedSecretScanner secretScanner) {
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
    this.secretScanner = secretScanner == null ? new GeneratedSecretScanner() : secretScanner;
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
        new ObjectMapper(),
        new GeneratedSecretScanner());
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

    SampleResult samples = sampleFiles(workspaceRoot, staticResult.findings(), scanId.toString());
    Set<String> languages = samples.languages();
    int totalLoc = samples.totalLoc();
    int totalFiles = samples.totalFiles();

    // RR-435 — a semgrep hit on a hardcoded-credential rule carries the matched line verbatim in
    // its snippet (and sometimes in its message). Redact both on the same rules the sampler uses,
    // and drop findings whose file the sample policy refuses to disclose.
    List<CodeReviewAgent.StaticFinding> staticForAgent =
        staticResult.findings().stream()
            .filter(
                f ->
                    f.filePath() == null || !SensitiveWorkspacePathPolicy.isSensitive(f.filePath()))
            .map(
                f ->
                    new CodeReviewAgent.StaticFinding(
                        f.ruleId(),
                        f.severity(),
                        f.category(),
                        f.filePath(),
                        f.lineStart(),
                        f.lineEnd(),
                        secretScanner.redactRecognizedSecrets(f.message()),
                        secretScanner.redactRecognizedSecrets(f.snippet())))
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
            // RR-1685 — carry WHY the walk ended so the prompt can state a bounded count as a
            // lower bound instead of asserting it as the repository's size.
            .scanCoverage(samples.coverage())
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
    return sampleFiles(root, staticFindings, null);
  }

  /**
   * @param auditKey short identifier (scan id) used only in the disclosure audit log line; may be
   *     null.
   */
  SampleResult sampleFiles(Path root, List<SemgrepRunner.Finding> staticFindings, String auditKey) {
    Set<String> languages = new HashSet<>();
    long totalLoc = 0;
    int totalFiles = 0;
    long totalReadBytes = 0;
    long deadline = System.currentTimeMillis() + MAX_SAMPLE_DURATION_MILLIS;
    // Boost score for files referenced by static findings — those are likely the hot spots. The
    // bump is applied at admission rather than after the walk (RR-433) so the retained set can be
    // bounded to MAX_FILES_IN_PROMPT while producing the same final ranking.
    // RR-435 — the same finding anchors also let us send only bounded line windows around each hit
    // for those files (anchorsByFile), rather than the whole head.
    Map<String, List<int[]>> anchorsByFile = anchorsByFile(staticFindings);
    Set<String> hotPaths = anchorsByFile.keySet();
    // Min-heap of the best MAX_FILES_IN_PROMPT candidates seen so far: worst (lowest score, then
    // latest walk position) sits at the head and is the one evicted.
    java.util.PriorityQueue<FileMeta> best =
        new java.util.PriorityQueue<>(
            Comparator.comparingInt(FileMeta::score)
                .thenComparing(Comparator.comparingLong(FileMeta::sequence).reversed()));
    long sequence = 0;
    // RR-1685 — the walk below is bounded three ways (file cap, read budget, wall clock). Record
    // WHY it ended so the counts are not later reported as a measured repository size.
    CodeReviewAgent.ScanCoverage coverage = CodeReviewAgent.ScanCoverage.COMPLETE;
    if (!Files.isDirectory(root)) {
      return new SampleResult(
          List.of(), languages, 0, 0, CodeReviewAgent.ScanCoverage.TRUNCATED_WALK_FAILED);
    }
    try (Stream<Path> stream = Files.walk(root)) {
      var iter = stream.iterator();
      while (iter.hasNext()) {
        if (totalFiles >= MAX_FILES_SCANNED) {
          coverage = CodeReviewAgent.ScanCoverage.TRUNCATED_FILE_LIMIT;
          break;
        }
        if (System.currentTimeMillis() > deadline) {
          coverage = CodeReviewAgent.ScanCoverage.TRUNCATED_TIME_BUDGET;
          log.warn(
              "CodeReview sampling stopped at the {}ms time budget after {} files",
              MAX_SAMPLE_DURATION_MILLIS,
              totalFiles);
          break;
        }
        Path p = iter.next();
        // RR-434 — the reviewed tree is a customer-controlled clone. Every following-link
        // operation below (isDirectory / size / readAllBytes) would happily resolve a repository
        // symlink to a readable host file and ship its contents to an external LLM. Files.walk
        // does not descend into symlinked directories, so rejecting the leaf link plus proving
        // real-path containment closes the escape.
        if (Files.isSymbolicLink(p)) continue;
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) continue;
        if (!SafeWorkspacePath.isWithin(root, p)) continue;
        Path rel = root.relativize(p);
        String relStr = rel.toString().replace('\\', '/');
        if (shouldSkip(relStr)) continue;
        String ext = extensionOf(relStr);
        String lang = LANG_BY_EXT.getOrDefault(ext, "");
        if (!lang.isBlank()) languages.add(lang);
        long size;
        try {
          size =
              Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).size();
        } catch (IOException e) {
          continue;
        }
        if (size > MAX_FILE_SIZE_BYTES) continue; // skip huge files (>1MB)
        totalFiles++;
        if (lang.isBlank()) continue;
        // RR-433 — the size is known BEFORE the file is opened, so the cumulative read budget is
        // enforced ahead of the allocation rather than after it.
        if (totalReadBytes + size > MAX_TOTAL_READ_BYTES) {
          coverage = CodeReviewAgent.ScanCoverage.TRUNCATED_READ_BUDGET;
          log.warn(
              "CodeReview sampling stopped at the {}-byte read budget after {} files",
              MAX_TOTAL_READ_BYTES,
              totalFiles);
          break;
        }
        List<int[]> anchors = anchorsByFile.get(relStr);
        FileExcerpt excerpt;
        try {
          excerpt =
              (anchors == null || anchors.isEmpty())
                  ? readBoundedHead(p)
                  : readBoundedWindows(p, anchors);
        } catch (IOException e) {
          continue; // skip unreadable
        }
        totalReadBytes += size;
        totalLoc += excerpt.lineCount();
        FileMeta candidate =
            new FileMeta(
                relStr,
                lang,
                excerpt.content(),
                scoreFile(relStr, excerpt.content()) + (hotPaths.contains(relStr) ? 50 : 0),
                sequence++,
                excerpt.disclosure());
        best.add(candidate);
        if (best.size() > MAX_FILES_IN_PROMPT) best.poll();
      }
    } catch (IOException e) {
      coverage = CodeReviewAgent.ScanCoverage.TRUNCATED_WALK_FAILED;
      log.warn("CodeReview file walk failed: {}", e.getMessage());
    }

    List<FileMeta> picked = new ArrayList<>(best);
    picked.sort(
        Comparator.comparingInt(FileMeta::score)
            .reversed()
            .thenComparing(Comparator.comparingLong(FileMeta::sequence)));
    List<CodeReviewAgent.FileSample> samples = new ArrayList<>(picked.size());
    for (FileMeta m : picked) {
      samples.add(new CodeReviewAgent.FileSample(m.path(), m.lang(), m.content()));
    }
    auditDisclosure(auditKey, picked);
    return new SampleResult(
        samples, languages, (int) Math.min(totalLoc, Integer.MAX_VALUE), totalFiles, coverage);
  }

  /**
   * RR-435 — record exactly which files and line ranges crossed the boundary to the review model.
   * This is written to the SERVER LOG only, never into the prompt, so the disclosure is auditable
   * after the fact (which repository content, and how much of it, was sent to a third-party
   * provider) without itself becoming a new disclosure channel. Paths and ranges only — no content.
   */
  private void auditDisclosure(String auditKey, List<FileMeta> picked) {
    if (picked.isEmpty()) {
      log.info("CodeReview disclosure scan={} sent 0 files to the review model", auditKey);
      return;
    }
    StringBuilder sb = new StringBuilder();
    int totalChars = 0;
    for (FileMeta m : picked) {
      if (sb.length() > 0) sb.append("; ");
      sb.append(m.path())
          .append(' ')
          .append(m.disclosure())
          .append(' ')
          .append(m.content().length())
          .append("ch");
      totalChars += m.content().length();
    }
    log.info(
        "CodeReview disclosure scan={} sent {} file(s) / {} chars to the review model: {}",
        auditKey,
        picked.size(),
        totalChars,
        sb);
  }

  /**
   * Group static-finding anchors by workspace-relative file path, keeping each finding's
   * [start,end] line span. Findings with no usable line span still register the file (empty list)
   * so it is scored as a hot spot but read as a bounded head when no window can be centred.
   */
  private static Map<String, List<int[]>> anchorsByFile(List<SemgrepRunner.Finding> findings) {
    Map<String, List<int[]>> byFile = new java.util.HashMap<>();
    if (findings == null) return byFile;
    for (SemgrepRunner.Finding f : findings) {
      if (f == null || f.filePath() == null) continue;
      String key = f.filePath().replace('\\', '/');
      List<int[]> ranges = byFile.computeIfAbsent(key, k -> new ArrayList<>());
      if (f.lineStart() > 0) {
        int end = Math.max(f.lineStart(), f.lineEnd());
        ranges.add(new int[] {f.lineStart(), end});
      }
    }
    return byFile;
  }

  /**
   * A bounded, already-redacted excerpt of a file plus the WHOLE file's line count and a
   * server-log-only descriptor of exactly what was retained (RR-435 — {@code disclosure} feeds the
   * audit line, never the prompt).
   */
  record FileExcerpt(String content, int lineCount, String disclosure) {}

  /**
   * Stream one candidate file, retaining at most {@link #MAX_BYTES_PER_FILE} characters while
   * counting every line (RR-433 — the previous implementation decoded the complete file into a
   * String and kept it alive until after the walk, purely to count lines and take a 12,000-char
   * prefix). The retained head is redacted before it is returned, so no un-redacted credential
   * material is ever held in the candidate set (RR-435).
   */
  private FileExcerpt readBoundedHead(Path p) throws IOException {
    StringBuilder head = new StringBuilder();
    int lines = 0;
    boolean truncated = false;
    try (var reader =
        new java.io.BufferedReader(
            new java.io.InputStreamReader(
                Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        lines++;
        if (head.length() >= MAX_BYTES_PER_FILE) {
          truncated = true;
          continue;
        }
        if (lines > 1) head.append('\n');
        head.append(line);
      }
    }
    if (head.length() > MAX_BYTES_PER_FILE) {
      head.setLength(MAX_BYTES_PER_FILE);
      truncated = true;
    }
    if (truncated) head.append("\n\n…(truncated)\n");
    int lastLine = truncated ? countLines(head) : lines;
    String descriptor = lines == 0 ? "HEAD (empty)" : "HEAD L1-" + Math.max(1, lastLine);
    return new FileExcerpt(
        secretScanner.redactRecognizedSecrets(head.toString()), lines, descriptor);
  }

  /**
   * RR-435 — for a static-finding hot-spot file, retain only bounded line windows around each
   * finding anchor (merged when overlapping) rather than the whole head. A large flagged file then
   * discloses only the relevant neighbourhoods to the review model. Every included line keeps its
   * 1-based number so the model can still anchor issues, the assembled excerpt is capped at {@link
   * #MAX_BYTES_PER_FILE} and redacted, and the returned descriptor names the exact ranges sent.
   */
  private FileExcerpt readBoundedWindows(Path p, List<int[]> anchors) throws IOException {
    // Expand each anchor by the context radius, then merge overlapping/adjacent windows.
    List<int[]> windows = new ArrayList<>();
    for (int[] a : anchors) {
      int start = Math.max(1, a[0] - FINDING_CONTEXT_LINES);
      int end = a[1] + FINDING_CONTEXT_LINES;
      windows.add(new int[] {start, end});
    }
    windows.sort(Comparator.comparingInt(w -> w[0]));
    List<int[]> merged = new ArrayList<>();
    for (int[] w : windows) {
      if (!merged.isEmpty() && w[0] <= merged.get(merged.size() - 1)[1] + 1) {
        int[] last = merged.get(merged.size() - 1);
        last[1] = Math.max(last[1], w[1]);
      } else {
        merged.add(new int[] {w[0], w[1]});
      }
    }

    StringBuilder body = new StringBuilder();
    List<int[]> included = new ArrayList<>();
    int totalLines = 0;
    boolean capped = false;
    int idx = 0; // pointer into merged windows
    Integer regionStart = null;
    int regionEnd = 0;
    try (var reader =
        new java.io.BufferedReader(
            new java.io.InputStreamReader(
                Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS), StandardCharsets.UTF_8))) {
      String line;
      int lineNo = 0;
      while ((line = reader.readLine()) != null) {
        lineNo++;
        totalLines = lineNo;
        // Advance past windows entirely before this line.
        while (idx < merged.size() && merged.get(idx)[1] < lineNo) idx++;
        boolean inWindow = idx < merged.size() && lineNo >= merged.get(idx)[0];
        if (!inWindow || capped) continue;
        if (body.length() >= MAX_BYTES_PER_FILE) {
          capped = true;
          continue;
        }
        if (regionStart == null) {
          regionStart = lineNo;
          if (body.length() > 0) body.append("\n");
          body.append("… [lines ")
              .append(merged.get(idx)[0])
              .append('-')
              .append(merged.get(idx)[1])
              .append("] …\n");
        }
        body.append(lineNo).append(": ").append(line).append('\n');
        regionEnd = lineNo;
        // Close the region descriptor when this is the last line of the current window.
        if (lineNo == merged.get(idx)[1]) {
          included.add(new int[] {regionStart, regionEnd});
          regionStart = null;
        }
      }
    }
    if (regionStart != null) included.add(new int[] {regionStart, regionEnd});
    if (body.length() > MAX_BYTES_PER_FILE) {
      body.setLength(MAX_BYTES_PER_FILE);
      capped = true;
    }
    if (capped) body.append("\n…(truncated)\n");
    String descriptor =
        included.isEmpty()
            ? "no-anchor-lines-present"
            : included.stream().map(r -> "L" + r[0] + "-" + r[1]).collect(Collectors.joining(","));
    if (capped) descriptor = descriptor + "(+truncated)";
    return new FileExcerpt(
        secretScanner.redactRecognizedSecrets(body.toString()), totalLines, descriptor);
  }

  /** Count newline-delimited lines in an already-built buffer (for a truncated head descriptor). */
  private static int countLines(CharSequence s) {
    if (s.length() == 0) return 0;
    int lines = 1;
    for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') lines++;
    return lines;
  }

  /** Skip rule for files we don't want walking into the prompt OR the LOC count. */
  static boolean shouldSkip(String relPath) {
    // RR-435 — credential stores, private keys and keystores are excluded from the review sample
    // outright. Reviewing an `.env` is not worth disclosing its contents to a third-party model,
    // and the same policy already governs what the worker's own tools may read. `.env.example`
    // and other declared templates stay inspectable.
    if (SensitiveWorkspacePathPolicy.isSensitive(relPath)) return true;
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
    // RR-435 — the `.env` term is gone: those paths are now excluded by
    // SensitiveWorkspacePathPolicy in shouldSkip, so boosting them was ranking material that can
    // never be sampled. Docker/config files remain review-worthy and are redacted before dispatch.
    if (lower.contains("docker") || lower.contains("config")) s += 15;
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

  /**
   * One retained sampling candidate: an already-truncated and already-redacted excerpt plus its
   * final score. {@code sequence} is the walk position, used as the tie-break so a bounded top-K
   * selection reproduces the ordering the old full-list stable sort produced.
   */
  record FileMeta(
      String path, String lang, String content, int score, long sequence, String disclosure) {}

  record SampleResult(
      List<CodeReviewAgent.FileSample> samples,
      Set<String> languages,
      int totalLoc,
      int totalFiles,
      /** RR-1685 — whether {@code totalLoc}/{@code totalFiles} are exact or lower bounds. */
      CodeReviewAgent.ScanCoverage coverage) {}

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
