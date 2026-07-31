package com.navneet.executespec.integration.scm;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.pr.PullRequestRecord;
import com.navneet.executespec.project.pr.PullRequestRecordRepository;
import com.navneet.executespec.project.pr.PullRequestRecordService;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.BitbucketRepoService;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.repo.ScmProvider;
import com.navneet.executespec.security.ArtifactPublicationGuard;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.entity.RunRepoSnapshot;
import com.navneet.executespec.state.repository.RunRepoSnapshotRepository;
import com.navneet.executespec.tool.GitTool;
import com.navneet.executespec.tool.GitToolResult;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * SCM push orchestration. Originally extracted from the V1 pipeline; now called from the V2 run
 * orchestrator's post-merge path.
 *
 * <p>Routes from the run's durable SCM/source identity. Credentials authorize the selected route;
 * they never choose it. Legacy runs without a durable route may infer one from an unambiguous
 * remote URL or a single configured provider. Used by the {@code /api/runs/{id}/push-scm} and
 * legacy {@code /push-github} endpoints.
 *
 * <p>Harness-agnostic — lives in the {@code integration.scm} package so it survives V1 deletion.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScmPushService {

  /**
   * Explicit result for the canonical {@code /push-scm} contract.
   *
   * <p>{@code publicationEligible} reports whether admission/trust checks passed; it does not claim
   * that the remote push succeeded. {@code retryable} means retrying after the accompanying
   * guidance can make progress. {@code code} is stable for clients; {@code message} remains
   * human-readable detail.
   */
  public record PushResult(
      String status,
      String scmProvider,
      String prUrl,
      String message,
      int attempted,
      int succeeded,
      int failed,
      String code,
      String blockingGate,
      boolean publicationEligible,
      boolean retryable,
      String retryGuidance,
      String remediation) {

    public static PushResult pushed(String provider, String prUrl, String message) {
      return new PushResult(
          "PUSHED",
          provider,
          prUrl == null ? "" : prUrl,
          message,
          1,
          1,
          0,
          "SCM_PUSHED",
          null,
          true,
          false,
          "",
          null);
    }

    public static PushResult failed(String message, String provider) {
      return failed(
          "SCM_PUSH_FAILED",
          "SCM_REMOTE_OPERATION",
          message,
          provider,
          "Correct the reported SCM failure, then retry the push.");
    }

    public static PushResult failed(
        String code, String blockingGate, String message, String provider, String remediation) {
      return new PushResult(
          "FAILED",
          provider == null ? "" : provider,
          "",
          message,
          1,
          0,
          1,
          code,
          blockingGate,
          true,
          true,
          remediation,
          remediation);
    }

    public static PushResult blocked(
        String code,
        String blockingGate,
        String message,
        String provider,
        boolean retryable,
        String remediation) {
      return blocked(code, blockingGate, message, provider, false, retryable, remediation);
    }

    private static PushResult blocked(
        String code,
        String blockingGate,
        String message,
        String provider,
        boolean publicationEligible,
        boolean retryable,
        String remediation) {
      return new PushResult(
          "FAILED",
          provider == null ? "" : provider,
          "",
          message,
          0,
          0,
          0,
          code,
          blockingGate,
          publicationEligible,
          retryable,
          remediation,
          remediation);
    }

    public static PushResult noConfiguration(String provider, String message) {
      return new PushResult(
          "NO_CONFIGURATION",
          provider == null ? "" : provider,
          "",
          message,
          0,
          0,
          0,
          "SCM_CONFIGURATION_REQUIRED",
          "SCM_CONFIGURATION",
          false,
          true,
          "Configure the repository provider and credentials, then retry the push.",
          "Configure the repository provider and credentials, then retry the push.");
    }

    public static PushResult partial(
        String provider, String prUrl, String message, int attempted, int succeeded, int failed) {
      return new PushResult(
          "PARTIAL",
          provider,
          prUrl == null ? "" : prUrl,
          message,
          attempted,
          succeeded,
          failed,
          "SCM_PUSH_PARTIAL",
          "MULTI_REPO_PUBLICATION",
          true,
          true,
          "Review the failed repository outcomes, correct them, then retry the push.",
          "Review the failed repository outcomes, correct them, then retry the push.");
    }

    public static PushResult noChanges(String provider, String message, int attempted) {
      return new PushResult(
          "NO_CHANGES",
          provider,
          "",
          message,
          attempted,
          0,
          0,
          "SCM_NO_CHANGES",
          "WORKSPACE_DIFF",
          true,
          false,
          "Create or restore a workspace change before attempting another push.",
          "Create or restore a workspace change before attempting another push.");
    }
  }

  private final GitTool gitTool;
  private final GitHubRepoService gitHubRepoService;
  private final BitbucketRepoService bitbucketRepoService;
  private final AgentProperties props;
  private final StateStore stateStore;
  private final SseBroadcaster broadcaster;
  private final AppUserRepository appUserRepository;
  private final ProjectRepository projectRepository;
  private final PullRequestRecordService pullRequestRecordService;
  private final PullRequestRecordRepository pullRequestRecordRepository;
  private final ProjectRepoRepository projectRepoRepository;
  private final ObjectMapper objectMapper;
  private final ArtifactPublicationGuard publicationGuard;
  private final BrownfieldHttpContractGuard brownfieldHttpContractGuard;
  private final PublicationAdmissionService publicationAdmissionService;
  // RL-2026-05-041 (audit #16) — enqueue orphan-branch cleanup when a push succeeds but PR fails.
  private final com.navneet.executespec.saga.SideEffectOutbox sideEffectOutbox;
  // RR-1668 — per-run cloned-branch SHA snapshots, used to pin --force-with-lease on the push.
  private final RunRepoSnapshotRepository runRepoSnapshotRepository;

  /**
   * RR-1668 — the exact remote SHA to pin a {@code --force-with-lease} against for {@code
   * branchName} in this repo, or {@code null} when this run did not clone that branch (so the push
   * must NOT force).
   *
   * <p>A run records a {@code run_repo_snapshot} row only for a branch it actually cloned,
   * capturing that branch's HEAD SHA at clone time. When the branch the run pushes to is that same
   * branch, the captured SHA is what the remote is expected to still be at — so a commit a human
   * pushed to the branch after the run cloned it makes the lease fail and the push abort, instead
   * of being silently overwritten. A fresh {@code executespec/<runId>} branch (or a re-run's {@code
   * branchNamingPattern} output) was never cloned, so there is no matching snapshot and this
   * returns {@code null}: the caller then does a plain, non-forced push that creates the branch
   * when absent and refuses a divergent remote rather than clobbering it.
   *
   * <p>Fails SAFE: any lookup error returns {@code null} (plain non-forced push), never a blind
   * force. A plain push still fast-forwards the run's own branch and rejects a human-advanced one,
   * so the null fallback keeps the RR-1668 protection.
   */
  private String expectedRemoteShaFor(String runId, String repoFullName, String branchName) {
    if (!StringUtils.hasText(runId) || !StringUtils.hasText(branchName)) return null;
    try {
      return runRepoSnapshotRepository.findByRunId(runId).stream()
          .filter(s -> branchName.equals(s.getBranch()))
          .filter(s -> repoFullName == null || sameRepo(repoFullName, s.getRepoFullName()))
          .map(RunRepoSnapshot::getCommitSha)
          .filter(StringUtils::hasText)
          .findFirst()
          .orElse(null);
    } catch (Exception e) {
      log.warn(
          "[{}] RR-1668 could not resolve clone snapshot for branch {} (defaulting to non-forced"
              + " push): {}",
          runId,
          branchName,
          e.getMessage());
      return null;
    }
  }

  /** Match a snapshot repo to a push repo by full name, tolerating {@code owner/repo} vs slug. */
  private static boolean sameRepo(String a, String b) {
    if (a == null || b == null) return false;
    if (a.equalsIgnoreCase(b)) return true;
    return lastPathSegment(a).equalsIgnoreCase(lastPathSegment(b));
  }

  /**
   * Entry point for manual SCM push from {@code RunController}. Resolves the user creds off the
   * run's creator, then delegates.
   */
  public PushResult push(PipelineRun run) {
    if (run == null) {
      return PushResult.blocked(
          "SCM_RUN_MISSING",
          "RUN_IDENTITY",
          "The run is missing",
          "",
          false,
          "Reload the run or start a new run before publishing.");
    }
    AppUser scmUser =
        run.getCreatedByUserId() != null
            ? appUserRepository.findById(run.getCreatedByUserId()).orElse(null)
            : null;
    return pushIfConfigured(run, scmUser);
  }

  /** Dispatch to the durable source provider; credentials only authorize that provider. */
  private PushResult pushIfConfigured(PipelineRun run, AppUser user) {
    try (PublicationAdmissionService.Admission admission =
        publicationAdmissionService.assertEligible(run)) {
      admission.assertOwned();
      return pushAdmitted(run, user);
    } catch (PublicationAdmissionService.PublicationBlockedException ineligible) {
      log.warn(
          "[{}] SCM publication blocked by {}: {}",
          run.getRunId(),
          ineligible.code(),
          ineligible.getMessage());
      return PushResult.blocked(
          ineligible.code(),
          ineligible.eligibility().blockingGate(),
          ineligible.getMessage(),
          run.getScmProvider(),
          false,
          "Regenerate validation on the current workspace and resolve the named deterministic gate"
              + " before retrying.");
    }
  }

  private PushResult pushAdmitted(PipelineRun run, AppUser user) {
    try {
      // Final fail-closed brownfield compatibility check. This runs before provider/credential
      // resolution and therefore before any branch, commit, push, or PR side effect.
      // Worker-authored
      // tests cannot override a removed admitted route.
      boolean brownfield =
          "EXISTING".equalsIgnoreCase(String.valueOf(run.getRepoMode()))
              || StringUtils.hasText(run.getSelectedRepoIds())
              || brownfieldHttpContractGuard.hasBaseline(run.getRunId());
      if (brownfield) brownfieldHttpContractGuard.assertCompatible(run.getRunId());
    } catch (BrownfieldHttpContractGuard.ContractCompatibilityException incompatible) {
      log.warn(
          "[{}] SCM publication blocked by {}: {}",
          run.getRunId(),
          incompatible.code(),
          incompatible.getMessage());
      broadcaster.emit(
          run.getRunId(),
          RunEvent.log(
              run.getRunId(),
              "scm",
              "Push blocked [" + incompatible.code() + "]: " + incompatible.getMessage()));
      return PushResult.blocked(
          incompatible.code(),
          "PROTECTED_CONTRACTS",
          incompatible.getMessage(),
          run.getScmProvider(),
          false,
          contractRetryGuidance(incompatible.code()));
    }
    ProviderDecision decision = resolveProviderDecision(run, user);
    if (decision.error() != null) {
      log.warn("[{}] SCM publication blocked: {}", run.getRunId(), decision.error());
      return PushResult.blocked(
          decision.code(),
          "SCM_PROVIDER_ROUTE",
          decision.error(),
          decision.provider(),
          decision.retryable(),
          decision.retryGuidance());
    }
    if (ScmProvider.BITBUCKET.name().equals(decision.provider())) {
      hydrateBitbucketCredentials(run, user);
      return pushToBitbucket(run);
    }
    return pushToGitHubIfConfigured(run, user);
  }

  private static String contractRetryGuidance(String code) {
    return switch (code) {
      case "BROWNFIELD_HTTP_CONTRACT_REMOVED" ->
          "Restore the removed HTTP route, or obtain an explicit breaking-change approval bound to"
              + " this run before publishing.";
      case "BROWNFIELD_BASELINE_MISSING",
              "BROWNFIELD_BASELINE_INVALID",
              "BROWNFIELD_BASELINE_DIGEST_MISMATCH",
              "BROWNFIELD_BASELINE_IDENTITY_MISMATCH" ->
          "Do not retry publication. Restore the admitted brownfield contract evidence or restart"
              + " from a newly admitted workspace.";
      default ->
          "Resolve the brownfield contract evidence failure before attempting publication again.";
    };
  }

  private record ProviderDecision(
      String provider, String code, String error, boolean retryable, String retryGuidance) {
    static ProviderDecision route(ScmProvider provider) {
      return new ProviderDecision(provider.name(), null, null, false, "");
    }

    static ProviderDecision blocked(
        String provider, String code, String error, boolean retryable, String retryGuidance) {
      return new ProviderDecision(
          provider == null ? "" : provider, code, error, retryable, retryGuidance);
    }
  }

  private ProviderDecision resolveProviderDecision(PipelineRun run, AppUser user) {
    List<java.util.UUID> selectedIds = parseRepoIds(run.getSelectedRepoIds());
    if (StringUtils.hasText(run.getSelectedRepoIds())) {
      if (selectedIds.isEmpty()) {
        return ProviderDecision.blocked(
            "",
            "SCM_REPOSITORY_IDENTITY_INVALID",
            "Selected repository identity is invalid",
            false,
            "Return to the repository picker and submit a new run with a valid repository.");
      }
      List<ProjectRepo> selectedRepos = projectRepoRepository.findAllById(selectedIds);
      if (selectedRepos.size() != selectedIds.size()) {
        return ProviderDecision.blocked(
            "",
            "SCM_REPOSITORY_IDENTITY_INCOMPLETE",
            "Selected repository identity is incomplete",
            true,
            "Restore the missing project repository link, then retry publication.");
      }
      Set<ScmProvider> providers =
          selectedRepos.stream()
              .map(ProjectRepo::getScmProvider)
              .map(ScmProvider::require)
              .collect(Collectors.toSet());
      if (providers.size() != 1) {
        return ProviderDecision.blocked(
            "",
            "SCM_MIXED_PROVIDERS_UNSUPPORTED",
            "Mixed-provider SCM publication is not supported",
            false,
            "Select repositories from one SCM provider in a new run.");
      }
      ScmProvider selectedProvider = providers.iterator().next();
      if (selectedProvider == ScmProvider.BITBUCKET) {
        return ProviderDecision.blocked(
            ScmProvider.BITBUCKET.name(),
            "SCM_BITBUCKET_PROJECT_PUBLICATION_UNSUPPORTED",
            "Bitbucket project publication is not supported on this release",
            false,
            "Use a supported GitHub project repository for this release.");
      }
      return ProviderDecision.route(selectedProvider);
    }

    if (StringUtils.hasText(run.getScmProvider())) {
      try {
        return ProviderDecision.route(
            ScmProvider.valueOf(run.getScmProvider().trim().toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException unknown) {
        return ProviderDecision.blocked(
            "",
            "SCM_PROVIDER_UNSUPPORTED",
            "Unsupported SCM provider on run",
            false,
            "Submit a new run with a supported SCM provider.");
      }
    }

    String repoUrl = run.getRepoUrl();
    if (StringUtils.hasText(repoUrl)) {
      String normalized = repoUrl.toLowerCase(Locale.ROOT);
      if (normalized.contains("bitbucket.org/")) {
        return ProviderDecision.route(ScmProvider.BITBUCKET);
      }
      if (normalized.contains("github.com/")) {
        return ProviderDecision.route(ScmProvider.GITHUB);
      }
    }

    boolean bitbucketConfigured = hasBitbucketConfiguration(run, user);
    boolean githubConfigured = StringUtils.hasText(resolvePat(run, user));
    if (bitbucketConfigured && githubConfigured) {
      return ProviderDecision.blocked(
          "",
          "SCM_LEGACY_ROUTE_AMBIGUOUS",
          "Legacy run has multiple SCM credentials and no durable provider route",
          false,
          "Do not retry this legacy run; submit a new run with an explicit SCM provider.");
    }
    return ProviderDecision.route(bitbucketConfigured ? ScmProvider.BITBUCKET : ScmProvider.GITHUB);
  }

  private static boolean hasBitbucketConfiguration(PipelineRun run, AppUser user) {
    return bitbucketComplete(run) || bitbucketComplete(user);
  }

  /** A complete Bitbucket identity has all three of username, workspace and app password. */
  private static boolean bitbucketComplete(PipelineRun run) {
    return run != null
        && StringUtils.hasText(run.getBitbucketUsername())
        && StringUtils.hasText(run.getBitbucketWorkspace())
        && StringUtils.hasText(run.getBitbucketAppPassword());
  }

  private static boolean bitbucketComplete(AppUser user) {
    return user != null
        && StringUtils.hasText(user.getBitbucketUsername())
        && StringUtils.hasText(user.getBitbucketWorkspace())
        && StringUtils.hasText(user.getBitbucketAppPassword());
  }

  private static void hydrateBitbucketCredentials(PipelineRun run, AppUser user) {
    // RR-867 — resolve ONE complete Bitbucket identity from ONE source. The old guard gated
    // hydration on `run.getBitbucketUsername()` alone, so a run carrying only a username (a partial
    // identity) suppressed a complete user credential and then push read a null workspace/password
    // from the still-partial run. A run that already holds a COMPLETE identity is authoritative and
    // untouched; a run with a partial (or empty) identity is replaced wholesale from the user's
    // complete config — never mixed field-by-field between two identities.
    if (bitbucketComplete(run)) return;
    if (!bitbucketComplete(user)) return;
    run.setBitbucketUsername(user.getBitbucketUsername());
    run.setBitbucketAppPassword(user.getBitbucketAppPassword());
    run.setBitbucketWorkspace(user.getBitbucketWorkspace());
    run.setBitbucketRepoSlug(user.getBitbucketRepoSlug());
  }

  private PushResult pushToBitbucket(PipelineRun run) {
    String workspace = run.getBitbucketWorkspace();
    String repoSlug = run.getBitbucketRepoSlug();
    String username = run.getBitbucketUsername();
    String appPassword = run.getBitbucketAppPassword();

    if (!StringUtils.hasText(repoSlug)) {
      log.warn(
          "[{}] Bitbucket workspace set but repo slug missing — skipping push", run.getRunId());
      return PushResult.noConfiguration("BITBUCKET", "Bitbucket repository is not configured");
    }

    String blockingGate = "WORKSPACE_SAFETY";
    try {
      // Audit 2026-05-12 review item P #78: GitHub path resolves branch name via
      // resolveBranchName(project, run) which honours Project.branchNamingPattern;
      // Bitbucket path was hardcoded to "executespec/<runId-short>". Match the contract here
      // so Bitbucket-deployed customers can override branch naming the same way.
      Project project =
          run.getProjectId() != null
              ? projectRepository.findById(run.getProjectId()).orElse(null)
              : null;
      String branchName = resolveBranchName(project, run);
      String cloneUrl = "https://bitbucket.org/" + workspace + "/" + repoSlug + ".git";
      String expectedRemoteSha =
          expectedRemoteShaFor(run.getRunId(), workspace + "/" + repoSlug, branchName);

      publicationGuard.assertGitWorkspaceSafe(run.getRunId());
      blockingGate = "GIT_PUSH";
      gitTool.pushToBranchBitbucket(
          run.getRunId(), cloneUrl, username, appPassword, branchName, expectedRemoteSha);

      String title =
          StringUtils.hasText(run.getRunTitle()) ? run.getRunTitle() : "ExecuteSpec changes";
      String body =
          StringUtils.hasText(run.getRunSummary()) ? run.getRunSummary() : run.getUserRequest();
      blockingGate = "PR_CREATION";
      String prUrl =
          bitbucketRepoService.createPullRequest(
              workspace, repoSlug, branchName, title, body, username, appPassword);

      if (prUrl != null) {
        run.setScmPrUrl(prUrl);
        run.setScmProvider("BITBUCKET");
        stateStore.save(run);
        log.info("[{}] Bitbucket PR created: {}", run.getRunId(), prUrl);
        broadcaster.emit(
            run.getRunId(),
            RunEvent.log(run.getRunId(), "scm", "Bitbucket pull request created: " + prUrl));
        return PushResult.pushed("BITBUCKET", prUrl, "Bitbucket pull request created");
      }
      return PushResult.failed(
          "SCM_PR_CREATION_FAILED",
          "PR_CREATION",
          "Bitbucket pull request was not created",
          "BITBUCKET",
          "Check Bitbucket pull-request permissions and branch policy, then retry.");
    } catch (Exception e) {
      log.warn("[{}] Bitbucket push failed: {}", run.getRunId(), e.getMessage());
      String code =
          "PR_CREATION".equals(blockingGate)
              ? "SCM_PR_CREATION_FAILED"
              : "GIT_PUSH".equals(blockingGate) ? "SCM_GIT_PUSH_FAILED" : "SCM_WORKSPACE_UNSAFE";
      String remediation =
          "PR_CREATION".equals(blockingGate)
              ? "Check Bitbucket pull-request permissions and branch policy, then retry."
              : "GIT_PUSH".equals(blockingGate)
                  ? "Check Bitbucket Git credentials, remote branch policy, and connectivity, then"
                      + " retry."
                  : "Restore a safe, current workspace before publishing.";
      if ("WORKSPACE_SAFETY".equals(blockingGate)) {
        return PushResult.blocked(
            code,
            blockingGate,
            "Bitbucket publication failed: " + safeMessage(e),
            "BITBUCKET",
            false,
            remediation);
      }
      return PushResult.failed(
          code,
          blockingGate,
          "Bitbucket publication failed: " + safeMessage(e),
          "BITBUCKET",
          remediation);
    }
  }

  /**
   * RR-900 — the run's repository mode, parsed exactly. The previous code compared the raw string
   * against {@code "EXISTING"} and sent EVERY other value — {@code null}, {@code "NONE"}, a typo, a
   * crafted value — down the direct-publication branch, which force-pushes the remote default
   * branch. A mode that is not understood must never resolve to the most destructive operation.
   */
  enum RepoMode {
    /** Publish into a repository ExecuteSpec creates for this run. */
    NEW,
    /** Publish as a branch + pull request against a repository the customer already owns. */
    EXISTING,
    /** No repository is attached to this run; publication is not defined. */
    NONE;

    /**
     * @return the parsed mode, or empty when the value is absent/blank (caller decides the safe
     *     default) — an unrecognised NON-blank value throws so it can never be defaulted.
     */
    static java.util.Optional<RepoMode> parse(String raw) {
      if (raw == null || raw.isBlank()) return java.util.Optional.empty();
      try {
        return java.util.Optional.of(RepoMode.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException unknown) {
        throw new UnknownRepoModeException(raw);
      }
    }
  }

  /** Raised when a run carries a repository mode this release does not understand. */
  static final class UnknownRepoModeException extends RuntimeException {
    UnknownRepoModeException(String raw) {
      super("Unsupported repository mode");
      this.raw = raw;
    }

    private final String raw;

    String raw() {
      return raw;
    }
  }

  private PushResult pushToGitHubIfConfigured(PipelineRun run, AppUser user) {
    // Pilot v3.0 — multi-repo runs: push each cloned subdirectory as its own
    // branch + PR. Falls through to the single-repo path if no project repos
    // were selected.
    if (StringUtils.hasText(run.getSelectedRepoIds())) {
      String pat = resolvePat(run, user);
      if (pat == null || pat.isBlank()) {
        log.warn("Multi-repo push requires a PAT — skipping for run {}", run.getRunId());
        return PushResult.noConfiguration("GITHUB", "GitHub PAT is not configured");
      }
      return pushMultiRepo(run, pat);
    }

    RepoMode repoMode;
    try {
      // Absent/blank is the legacy shape and stays permitted, but it resolves to the SAFE mode
      // (branch + PR), never to direct publication onto the default branch.
      repoMode = RepoMode.parse(run.getRepoMode()).orElse(RepoMode.EXISTING);
    } catch (UnknownRepoModeException unknown) {
      log.warn(
          "[{}] SCM publication refused — unsupported repository mode {}",
          run.getRunId(),
          unknown.raw());
      return PushResult.blocked(
          "SCM_REPOSITORY_MODE_UNSUPPORTED",
          "REPOSITORY_IDENTITY",
          "Unsupported repository mode on run — publication refused",
          "GITHUB",
          false,
          "Select NEW, EXISTING, or NONE and retry the publication request.");
    }
    if (repoMode == RepoMode.NONE) {
      return PushResult.noConfiguration("GITHUB", "Run has no attached repository");
    }
    String repoUrl = run.getRepoUrl();

    if (!StringUtils.hasText(repoUrl) && repoMode != RepoMode.NEW)
      return PushResult.noConfiguration("GITHUB", "GitHub repository is not configured");

    String pat = resolvePat(run, user);
    String owner =
        (run.getGithubOwner() != null && !run.getGithubOwner().isBlank())
            ? run.getGithubOwner()
            : (props.getGithub() != null ? props.getGithub().getDefaultOwner() : null);

    if (pat == null || pat.isBlank()) {
      log.warn("GitHub PAT not configured — skipping push for run {}", run.getRunId());
      return PushResult.noConfiguration("GITHUB", "GitHub PAT is not configured");
    }

    try {
      publicationGuard.assertGitWorkspaceSafe(run.getRunId());
    } catch (Exception unsafe) {
      log.warn("[{}] SCM publication rejected: {}", run.getRunId(), unsafe.getMessage());
      return PushResult.blocked(
          "SCM_WORKSPACE_UNSAFE",
          "WORKSPACE_SAFETY",
          unsafe.getMessage(),
          "GITHUB",
          false,
          "Restore a safe, current workspace before publishing.");
    }

    String remote = repoUrl;
    String branchName = null;
    String blockingGate = "REPOSITORY_RESOLUTION";
    boolean branchPushed = false;
    boolean pullRequestEstablished = false;
    boolean orphanCleanupEnqueued = false;
    try {
      // RR-900 — direct publication force-pushes the default branch, so it is permitted ONLY for a
      // repository this invocation just created (and which is therefore empty). A NEW-mode run
      // pointed at a pre-existing repository falls through to the branch + PR path.
      boolean createdThisInvocation = false;
      if (repoMode == RepoMode.NEW && (repoUrl == null || repoUrl.isBlank())) {
        blockingGate = "REPOSITORY_CREATION";
        log.info("Creating new GitHub repo for run {} (owner={})", run.getRunId(), owner);
        remote = gitHubRepoService.createRepo(run.getUserRequest(), null, "private", pat, owner);
        if (remote != null) {
          createdThisInvocation = true;
          blockingGate = "REPOSITORY_PERSISTENCE";
          PipelineRun freshRun = stateStore.findRun(run.getRunId()).orElse(run);
          freshRun.setRepoUrl(remote);
          stateStore.save(freshRun);
          run.setRepoUrl(remote);
          log.info("Created GitHub repo: {}", remote);
        }
      }

      if (remote == null || remote.isBlank()) {
        log.warn("No remote URL available — skipping push for run {}", run.getRunId());
        return PushResult.failed(
            "SCM_REPOSITORY_UNRESOLVED",
            "REPOSITORY_RESOLUTION",
            "GitHub repository could not be resolved",
            "GITHUB",
            "Reconnect or select the GitHub repository, then retry.");
      }

      log.info("Pushing workspace to GitHub: {}", remote);

      if (!createdThisInvocation) {
        // Pilot R5 — project settings may override branch naming / target branch / reviewers.
        Project project =
            run.getProjectId() != null
                ? projectRepository.findById(run.getProjectId()).orElse(null)
                : null;
        branchName = resolveBranchName(project, run);
        String expectedRemoteSha =
            expectedRemoteShaFor(run.getRunId(), repoFullNameFromUrl(remote), branchName);
        blockingGate = "GIT_PUSH";
        GitToolResult result =
            gitTool.pushToBranch(run.getRunId(), remote, pat, branchName, expectedRemoteSha);
        if (!result.isOk()) {
          log.warn("GitHub branch push failed for run {}: {}", run.getRunId(), result.value());
          return PushResult.failed(
              "SCM_GIT_PUSH_FAILED",
              "GIT_PUSH",
              "GitHub branch push failed: " + result.value(),
              "GITHUB",
              "Check GitHub Git credentials, remote branch policy, and connectivity, then retry.");
        }
        branchPushed = true;
        blockingGate = "DEFAULT_BRANCH_RESOLUTION";
        String targetBranch =
            (project != null && StringUtils.hasText(project.getTargetBranch()))
                ? project.getTargetBranch()
                : gitHubRepoService.getDefaultBranch(remote, pat);
        String prTitle = run.getRunTitle() != null ? run.getRunTitle() : "ExecuteSpec changes";
        String prBody = run.getRunSummary() != null ? run.getRunSummary() : run.getUserRequest();
        // RR-146 — an existing PR for this head is NOT a failure. Only a typed FAILED outcome may
        // authorize deleting the branch we just pushed.
        blockingGate = "PR_CREATION";
        GitHubRepoService.PullRequestCreation creation =
            gitHubRepoService.createPullRequestDetailed(
                remote, branchName, targetBranch, prTitle, prBody, pat);
        String prUrl = creation.prUrl();
        if (creation.prExists() && prUrl != null) {
          pullRequestEstablished = true;
          blockingGate = "PR_STATE_PERSISTENCE";
          PipelineRun freshRun = stateStore.findRun(run.getRunId()).orElse(run);
          freshRun.setGithubPrUrl(prUrl);
          freshRun.setScmPrUrl(prUrl);
          freshRun.setScmProvider("GITHUB");
          stateStore.save(freshRun);
          run.setGithubPrUrl(prUrl);
          run.setScmPrUrl(prUrl);
          run.setScmProvider("GITHUB");
          log.info("Created PR for run {}: {}", run.getRunId(), prUrl);
          broadcaster.emit(
              run.getRunId(),
              RunEvent.log(run.getRunId(), "scm", "Pull request created: " + prUrl));

          // Persist a PullRequestRecord so the UI can show multi-PR status
          // and the GitHub webhook can flip it to MERGED/CLOSED later.
          blockingGate = "PR_RECORD_PERSISTENCE";
          String repoFullName = repoFullNameFromUrl(remote);
          pullRequestRecordService.recordCreated(
              run.getRunId(), null, repoFullName, prUrl, branchName, targetBranch);

          // Request default reviewers from project settings (best-effort).
          List<String> reviewers = parseReviewers(project);
          if (!reviewers.isEmpty()) {
            Integer prNumber =
                com.navneet.executespec.project.pr.PullRequestRecordService.extractPrNumber(prUrl);
            if (prNumber != null) {
              blockingGate = "REVIEWER_REQUEST";
              gitHubRepoService.requestReviewers(remote, prNumber, reviewers, pat);
            }
          }
          blockingGate = "REPOSITORY_DESCRIPTION";
          gitHubRepoService.updateRepoDescription(remote, run.getUserRequest(), pat);
          return PushResult.pushed(
              "GITHUB",
              prUrl,
              creation.outcome() == GitHubRepoService.PullRequestCreation.Outcome.ALREADY_EXISTS
                  ? "Existing GitHub pull request updated"
                  : "GitHub pull request created");
        } else if (creation.prExists()) {
          pullRequestEstablished = true;
          // ALREADY_EXISTS but the discovery lookup could not be answered. A pull request is open
          // on
          // this branch, so cleanup is forbidden; we simply cannot report its URL.
          log.info(
              "[{}] Pull request already exists for branch {} — URL unresolved, no cleanup"
                  + " enqueued",
              run.getRunId(),
              branchName);
          blockingGate = "REPOSITORY_DESCRIPTION";
          gitHubRepoService.updateRepoDescription(remote, run.getUserRequest(), pat);
          return PushResult.pushed(
              "GITHUB", "", "Branch pushed to an existing GitHub pull request");
        } else {
          // RL-2026-05-041 (audit #16) — the branch pushed but PR creation definitively failed:
          // it's
          // now an orphan. Enqueue the saga compensation to delete it (PAT resolved from the run
          // owner at execution time — not stored in the payload). RR-146: reached only for a typed
          // FAILED outcome, never for "a pull request already exists".
          enqueueOrphanBranchCleanup(run.getRunId(), repoFullNameFromUrl(remote), branchName);
          orphanCleanupEnqueued = true;
          blockingGate = "REPOSITORY_DESCRIPTION";
          gitHubRepoService.updateRepoDescription(remote, run.getUserRequest(), pat);
          return PushResult.failed(
              "SCM_PR_CREATION_FAILED",
              "PR_CREATION",
              "GitHub branch pushed but pull request creation failed",
              "GITHUB",
              "Check GitHub pull-request permissions and branch policy, then retry.");
        }
      } else {
        blockingGate = "GIT_PUSH";
        GitToolResult result = gitTool.pushToRemote(run.getRunId(), remote, pat);
        if (!result.isOk()) {
          log.warn("GitHub push failed for run {}: {}", run.getRunId(), result.value());
          return PushResult.failed(
              "SCM_GIT_PUSH_FAILED",
              "GIT_PUSH",
              "GitHub push failed: " + result.value(),
              "GITHUB",
              "Check GitHub Git credentials, remote branch policy, and connectivity, then retry.");
        }
        return PushResult.pushed("GITHUB", "", "GitHub changes pushed");
      }
    } catch (Exception failure) {
      if (branchPushed && !pullRequestEstablished && !orphanCleanupEnqueued && branchName != null) {
        enqueueOrphanBranchCleanup(run.getRunId(), repoFullNameFromUrl(remote), branchName);
      }
      log.warn(
          "[{}] GitHub publication failed at stage {}: {}",
          run.getRunId(),
          blockingGate,
          safeMessage(failure));
      return githubStageFailure(blockingGate);
    }
  }

  private static PushResult githubStageFailure(String blockingGate) {
    String code =
        switch (blockingGate) {
          case "REPOSITORY_CREATION" -> "SCM_REPOSITORY_CREATION_FAILED";
          case "REPOSITORY_PERSISTENCE" -> "SCM_REPOSITORY_PERSISTENCE_FAILED";
          case "DEFAULT_BRANCH_RESOLUTION" -> "SCM_DEFAULT_BRANCH_FAILED";
          case "GIT_PUSH" -> "SCM_GIT_PUSH_FAILED";
          case "PR_CREATION" -> "SCM_PR_CREATION_FAILED";
          case "PR_STATE_PERSISTENCE", "PR_RECORD_PERSISTENCE" -> "SCM_PR_PERSISTENCE_FAILED";
          case "REVIEWER_REQUEST" -> "SCM_REVIEWER_REQUEST_FAILED";
          case "REPOSITORY_DESCRIPTION" -> "SCM_REPOSITORY_DESCRIPTION_FAILED";
          default -> "SCM_REPOSITORY_UNRESOLVED";
        };
    String remediation =
        switch (blockingGate) {
          case "REPOSITORY_CREATION" ->
              "Check GitHub repository-creation permission and organization policy, then retry.";
          case "REPOSITORY_PERSISTENCE" ->
              "The repository may exist remotely; reconcile the run repository URL before"
                  + " retrying.";
          case "DEFAULT_BRANCH_RESOLUTION" ->
              "Check repository access and default-branch configuration, then retry.";
          case "GIT_PUSH" ->
              "Check GitHub Git credentials, remote branch policy, and connectivity, then retry.";
          case "PR_CREATION" ->
              "Check GitHub pull-request permissions and branch policy, then retry.";
          case "PR_STATE_PERSISTENCE", "PR_RECORD_PERSISTENCE" ->
              "The pull request may already exist; refresh SCM state before retrying publication.";
          case "REVIEWER_REQUEST" ->
              "The pull request exists; correct reviewer access or project reviewer settings.";
          case "REPOSITORY_DESCRIPTION" ->
              "The Git publication may have succeeded; retry repository metadata synchronization.";
          default -> "Reconnect or select the GitHub repository, then retry.";
        };
    return PushResult.failed(
        code,
        blockingGate,
        "GitHub publication failed during " + blockingGate,
        "GITHUB",
        remediation);
  }

  private static String safeMessage(Exception e) {
    return e.getMessage() == null || e.getMessage().isBlank()
        ? e.getClass().getSimpleName()
        : e.getMessage();
  }

  /**
   * PAT precedence: explicit on-run value → run creator's stored PAT (re-read fresh in case the
   * user added it after submission) → platform fallback. Without the user lookup, runs submitted
   * before the user pasted their PAT silently fail to push and no PR is opened.
   */
  private String resolvePat(PipelineRun run, AppUser user) {
    if (run.getGithubPat() != null && !run.getGithubPat().isBlank()) return run.getGithubPat();
    if (user == null && run.getCreatedByUserId() != null) {
      user = appUserRepository.findById(run.getCreatedByUserId()).orElse(null);
    }
    if (user != null && user.getGithubPat() != null && !user.getGithubPat().isBlank()) {
      return user.getGithubPat();
    }
    // No system-PAT fallback — every GitHub push uses the user's own PAT (run- or user-stamped).
    // null = no credential: the caller skips the push + opens no PR (logged), never a platform
    // token.
    return null;
  }

  /**
   * Resolve the push branch name from {@link Project#getBranchNamingPattern()} (supporting {@code
   * {runId}} and {@code {description}} placeholders) or the legacy {@code
   * executespec/<runId-short>} default.
   */
  private static String resolveBranchName(Project project, PipelineRun run) {
    String pattern = project != null ? project.getBranchNamingPattern() : null;
    String shortId = run.getRunId().substring(0, Math.min(8, run.getRunId().length()));
    if (!StringUtils.hasText(pattern)) {
      return "executespec/" + shortId;
    }
    String description =
        slugify(run.getRunTitle() != null ? run.getRunTitle() : run.getUserRequest());
    return pattern.replace("{runId}", shortId).replace("{description}", description);
  }

  /** Converts a free-text title into a git-safe slug (≤40 chars). */
  private static String slugify(String text) {
    if (text == null) return "change";
    String slug = text.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    if (slug.isBlank()) return "change";
    return slug.length() > 40 ? slug.substring(0, 40) : slug;
  }

  /** Extracts {@code owner/repo} from an HTTPS GitHub URL. */
  private static String repoFullNameFromUrl(String url) {
    if (url == null) return "";
    String stripped = url.replaceFirst("https?://[^/]+/", "");
    if (stripped.endsWith(".git")) stripped = stripped.substring(0, stripped.length() - 4);
    int slash = stripped.indexOf('/');
    if (slash < 0) return stripped;
    int second = stripped.indexOf('/', slash + 1);
    return second < 0 ? stripped : stripped.substring(0, second);
  }

  /**
   * RL-2026-05-041 (audit #16) — enqueue a saga compensation to delete a branch that was pushed but
   * never got a PR (creation failed). Best-effort: a failed enqueue must never break the push path.
   * Payload carries repoFullName + branch only; the executor resolves the owner PAT at run time.
   */
  private void enqueueOrphanBranchCleanup(String runId, String repoFullName, String branch) {
    if (repoFullName == null || repoFullName.isBlank() || branch == null || branch.isBlank())
      return;
    try {
      sideEffectOutbox.enqueueCompensation(
          runId,
          "COMPENSATE_GITHUB_BRANCH",
          java.util.Map.of("repoFullName", repoFullName, "branch", branch));
      log.info(
          "[{}] Enqueued orphan-branch cleanup for {} branch {} (PR creation failed)",
          runId,
          repoFullName,
          branch);
    } catch (Exception e) {
      log.warn(
          "[{}] Failed to enqueue orphan-branch cleanup for {} branch {}: {}",
          runId,
          repoFullName,
          branch,
          e.getMessage());
    }
  }

  /**
   * Pilot v3.0 — push each linked repo's cloned subdirectory under {@code
   * workspace/<runId>/repos/<name>/} to its own branch + open a PR. Cross-links the PRs by patching
   * each body to reference the others. Failure of one repo does not abort the others.
   */
  private PushResult pushMultiRepo(PipelineRun run, String pat) {
    Project project =
        run.getProjectId() != null
            ? projectRepository.findById(run.getProjectId()).orElse(null)
            : null;
    List<java.util.UUID> ids = parseRepoIds(run.getSelectedRepoIds());
    if (ids.isEmpty())
      return PushResult.noConfiguration("GITHUB", "No linked repositories configured");

    String branchName = resolveBranchName(project, run);
    String runTitle =
        StringUtils.hasText(run.getRunTitle()) ? run.getRunTitle() : "ExecuteSpec changes";
    String prTitle = runTitle;
    String prBodyBase =
        StringUtils.hasText(run.getRunSummary()) ? run.getRunSummary() : run.getUserRequest();
    List<String> reviewers = parseReviewers(project);

    record Created(ProjectRepo repo, String prUrl, Integer prNumber) {}
    List<Created> created = new java.util.ArrayList<>();
    int attempted = 0;
    int failed = 0;
    int clean = 0;

    for (java.util.UUID repoId : ids) {
      ProjectRepo repo = projectRepoRepository.findById(repoId).orElse(null);
      if (repo == null) {
        failed++;
        continue;
      }
      attempted++;
      String subPath = "repos/" + lastPathSegment(repo.getGithubRepoFullName());
      String remote = "https://github.com/" + repo.getGithubRepoFullName();

      // 2026-05-02 — Per-repo commit messages (user ask): the message body is the file list
      // for THIS repo only, plus a Co-Authored-By trailer crediting admin@executespec.ai. Avoids
      // the previous behaviour where every repo got the same generic "ExecuteSpec changes" /
      // run-title message regardless of what actually changed in that repo.
      String commitMsg = buildPerRepoCommitMessage(run, runTitle, repo, subPath);

      try {
        publicationGuard.assertGitWorkspaceSafe(run.getRunId(), subPath);
      } catch (Exception unsafe) {
        log.warn(
            "[{}] SCM publication rejected for {}: {}",
            run.getRunId(),
            repo.getGithubRepoFullName(),
            unsafe.getMessage());
        broadcaster.emit(
            run.getRunId(),
            RunEvent.log(
                run.getRunId(),
                "scm",
                "Push blocked for " + repo.getGithubRepoFullName() + ": " + unsafe.getMessage()));
        failed++;
        continue;
      }

      String expectedRemoteSha =
          expectedRemoteShaFor(run.getRunId(), repo.getGithubRepoFullName(), branchName);
      var pushResult =
          gitTool.commitAndPushSubdir(
              run.getRunId(), subPath, remote, pat, branchName, commitMsg, expectedRemoteSha);
      if (!pushResult.isOk()) {
        log.warn(
            "[{}] multi-repo push failed for {}: {}",
            run.getRunId(),
            repo.getGithubRepoFullName(),
            pushResult.value());
        broadcaster.emit(
            run.getRunId(),
            RunEvent.log(
                run.getRunId(),
                "scm",
                "Push failed for " + repo.getGithubRepoFullName() + ": " + pushResult.value()));
        failed++;
        continue;
      }
      if ("CLEAN".equals(pushResult.value())) {
        log.info(
            "[{}] no changes for {}, skipping PR", run.getRunId(), repo.getGithubRepoFullName());
        clean++;
        continue;
      }

      String targetBranch =
          (project != null && StringUtils.hasText(project.getTargetBranch()))
              ? project.getTargetBranch()
              : (repo.getDefaultBranch() != null ? repo.getDefaultBranch() : "main");
      GitHubRepoService.PullRequestCreation creation =
          gitHubRepoService.createPullRequestDetailed(
              remote, branchName, targetBranch, prTitle, prBodyBase, pat);
      if (creation.failed()) {
        // RL-2026-05-041 (audit #16) — branch pushed but PR creation failed → orphan branch.
        // RR-146: only a typed FAILED outcome reaches here; "a pull request already exists" does
        // not, because that branch is live review material.
        enqueueOrphanBranchCleanup(run.getRunId(), repo.getGithubRepoFullName(), branchName);
        failed++;
        continue;
      }
      String prUrl = creation.prUrl();
      if (prUrl == null) {
        log.info(
            "[{}] Pull request already exists for {} branch {} — URL unresolved",
            run.getRunId(),
            repo.getGithubRepoFullName(),
            branchName);
        continue;
      }

      Integer prNumber = PullRequestRecordService.extractPrNumber(prUrl);
      PullRequestRecord record =
          pullRequestRecordService.recordCreated(
              run.getRunId(),
              repo.getId(),
              repo.getGithubRepoFullName(),
              prUrl,
              branchName,
              targetBranch);
      if (record != null) created.add(new Created(repo, prUrl, prNumber));

      if (!reviewers.isEmpty() && prNumber != null) {
        gitHubRepoService.requestReviewers(remote, prNumber, reviewers, pat);
      }
      broadcaster.emit(
          run.getRunId(),
          RunEvent.log(
              run.getRunId(),
              "scm",
              "PR opened for " + repo.getGithubRepoFullName() + ": " + prUrl));
    }

    // Cross-link: patch each PR body with the list of sibling PRs.
    if (created.size() > 1) {
      String siblingList =
          created.stream()
              .map(c -> "- " + c.repo().getGithubRepoFullName() + ": " + c.prUrl())
              .collect(java.util.stream.Collectors.joining("\n"));
      for (Created c : created) {
        if (c.prNumber() == null) continue;
        String body =
            (prBodyBase == null ? "" : prBodyBase)
                + "\n\n---\n**Multi-repo change** — sibling PRs:\n"
                + siblingList;
        gitHubRepoService.updatePullRequestBody(
            "https://github.com/" + c.repo().getGithubRepoFullName(), c.prNumber(), body, pat);
      }
    }

    // 2026-05-10 — persist a representative PR url on `pipeline_run.github_pr_url`. The
    // single-repo path (lines 200–212 above) already does this when its single PR succeeds,
    // but pushMultiRepo previously left the column null. Net effect on dev run 61c9fffa
    // (3-repo project): two PRs were opened (pplai-api/pull/23, frontend/pull/21) and
    // persisted to `pull_request_record`, but the v3 RunSidePanel reads
    // `detail.githubPrUrl` as the primary signal — null → no "Open PR" button visible to
    // the user, even though the PRs existed on GitHub. Pick the first PR as the
    // representative URL (matches the cross-link summary order); the full per-repo list
    // is still available via `GET /api/runs/{id}/prs`.
    if (!created.isEmpty()) {
      String firstPrUrl = created.get(0).prUrl();
      try {
        PipelineRun freshRun = stateStore.findRun(run.getRunId()).orElse(run);
        if (freshRun.getGithubPrUrl() == null || freshRun.getGithubPrUrl().isBlank()) {
          freshRun.setGithubPrUrl(firstPrUrl);
        }
        if (freshRun.getScmPrUrl() == null || freshRun.getScmPrUrl().isBlank()) {
          freshRun.setScmPrUrl(firstPrUrl);
        }
        if (freshRun.getScmProvider() == null) {
          freshRun.setScmProvider("GITHUB");
        }
        stateStore.save(freshRun);
        // Mirror onto the in-memory `run` reference so callers (e.g. test code,
        // event listeners) see the same value without a re-read.
        if (run.getGithubPrUrl() == null || run.getGithubPrUrl().isBlank()) {
          run.setGithubPrUrl(firstPrUrl);
        }
        if (run.getScmPrUrl() == null || run.getScmPrUrl().isBlank()) {
          run.setScmPrUrl(firstPrUrl);
        }
        if (run.getScmProvider() == null) {
          run.setScmProvider("GITHUB");
        }
        log.info(
            "[{}] persisted representative PR url on run row: {} (total {} PRs)",
            run.getRunId(),
            firstPrUrl,
            created.size());
      } catch (Exception persistEx) {
        log.warn(
            "[{}] failed to persist representative PR url: {}",
            run.getRunId(),
            persistEx.getMessage());
      }
    }

    String representativePr = created.isEmpty() ? "" : created.get(0).prUrl();
    if (!created.isEmpty() && failed > 0) {
      return PushResult.partial(
          "GITHUB",
          representativePr,
          "Some repositories pushed successfully; others failed",
          attempted,
          created.size(),
          failed);
    }
    if (!created.isEmpty()) {
      return PushResult.pushed("GITHUB", representativePr, "GitHub pull requests created");
    }
    if (failed > 0) {
      return PushResult.failed(
          "SCM_MULTI_REPO_PUBLICATION_FAILED",
          "MULTI_REPO_PUBLICATION",
          "No GitHub pull requests were created",
          "GITHUB",
          "Review each repository failure, correct credentials or branch policy, then retry.");
    }
    return PushResult.noChanges(
        "GITHUB", "No repository changes to push", attempted == 0 ? clean : attempted);
  }

  private List<java.util.UUID> parseRepoIds(String json) {
    if (json == null || json.isBlank()) return List.of();
    try {
      return objectMapper.readValue(
          json,
          objectMapper.getTypeFactory().constructCollectionType(List.class, java.util.UUID.class));
    } catch (Exception e) {
      log.warn("Failed to parse selected_repo_ids: {}", e.getMessage());
      return List.of();
    }
  }

  private static String lastPathSegment(String fullName) {
    int slash = fullName.lastIndexOf('/');
    return slash >= 0 ? fullName.substring(slash + 1) : fullName;
  }

  /** Parse the {@code defaultReviewers} JSON array stored on the project. */
  private List<String> parseReviewers(Project project) {
    if (project == null || !StringUtils.hasText(project.getDefaultReviewers())) return List.of();
    try {
      return objectMapper.readValue(
          project.getDefaultReviewers(),
          objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    } catch (Exception e) {
      log.warn("Failed to parse project.defaultReviewers: {}", e.getMessage());
      return List.of();
    }
  }

  /**
   * Co-author trailer added to every per-repo commit so the agent's authorship is credited and
   * GitHub's avatar / search surfaces both the human owner and the bot. Address chosen to match the
   * SUPER_ADMIN account that drives most ExecuteSpec test runs; can be made org-configurable later
   * if customers want a different handle.
   */
  private static final String CO_AUTHOR_TRAILER =
      "Co-Authored-By: ExecuteSpec Agent <admin@executespec.ai>";

  /**
   * 2026-05-02 — build a repo-scoped commit message:
   *
   * <pre>
   * &lt;run title&gt; (&lt;repo short&gt;)
   *
   * Repo: &lt;owner/repo&gt;
   * Run:  &lt;runIdShort&gt;
   *
   * Files changed (N):
   *   &lt;path1&gt;
   *   &lt;path2&gt;
   *   ...
   *
   * Co-Authored-By: ExecuteSpec Agent &lt;admin@executespec.ai&gt;
   * </pre>
   *
   * <p>The file list is enumerated via {@code git status --porcelain} on the per-repo subdir
   * (everything is staged just before this; {@code commitAndPushSubdir} does {@code add -A} after
   * we return). Capped at 30 lines so a 9605-file leak (b171fa52 class) doesn't blow the commit
   * message past GitHub's 100KB body limit.
   */
  private String buildPerRepoCommitMessage(
      PipelineRun run, String runTitle, ProjectRepo repo, String subPath) {
    String shortRepo = lastPathSegment(repo.getGithubRepoFullName());
    String runIdShort =
        run.getRunId() != null && run.getRunId().length() >= 8
            ? run.getRunId().substring(0, 8)
            : run.getRunId();
    StringBuilder sb = new StringBuilder();
    sb.append(runTitle).append(" (").append(shortRepo).append(")\n\n");
    sb.append("Repo: ").append(repo.getGithubRepoFullName()).append("\n");
    sb.append("Run:  ").append(runIdShort).append("\n");
    List<String> files = listPerRepoChangedFiles(run.getRunId(), subPath);
    if (!files.isEmpty()) {
      sb.append("\nFiles changed (").append(files.size()).append("):\n");
      int cap = Math.min(files.size(), 30);
      for (int i = 0; i < cap; i++) {
        sb.append("  ").append(files.get(i)).append("\n");
      }
      if (files.size() > cap) {
        sb.append("  ... ").append(files.size() - cap).append(" more\n");
      }
    }
    sb.append("\n").append(CO_AUTHOR_TRAILER).append("\n");
    return sb.toString();
  }

  /**
   * Read the staged + untracked file list inside the per-repo subdir via {@code git status
   * --porcelain --untracked-files=all}. Best-effort: returns an empty list on any subprocess
   * failure (the commit message just degrades to title + repo + co-author trailer).
   */
  private List<String> listPerRepoChangedFiles(String runId, String subPath) {
    try {
      java.nio.file.Path repoDir =
          java.nio.file.Path.of(props.getWorkspace(), runId, subPath).toAbsolutePath().normalize();
      if (!java.nio.file.Files.isDirectory(repoDir.resolve(".git"))) return List.of();
      ProcessBuilder pb =
          new ProcessBuilder("git", "status", "--porcelain", "--untracked-files=all")
              .directory(repoDir.toFile())
              .redirectErrorStream(true);
      Process p = pb.start();
      List<String> out = new java.util.ArrayList<>();
      try (var br =
          new java.io.BufferedReader(
              new java.io.InputStreamReader(
                  p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
        String line;
        while ((line = br.readLine()) != null) {
          if (line.length() < 4) continue;
          String path = line.substring(3).trim();
          // Renames look like "old -> new" — keep just the new path.
          int sep = path.indexOf(" -> ");
          if (sep > 0) path = path.substring(sep + 4).trim();
          if (path.startsWith("\"") && path.endsWith("\"")) {
            path = path.substring(1, path.length() - 1);
          }
          out.add(path);
        }
      }
      if (!p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
        p.destroyForcibly();
        return List.of();
      }
      return out;
    } catch (Exception e) {
      log.debug("listPerRepoChangedFiles failed run={} sub={}: {}", runId, subPath, e.getMessage());
      return List.of();
    }
  }

  // Apply-suggestion push (Fix L, 2026-05-03)

  /**
   * Per-repo outcome for an apply-time push. Mirrors the shape of {@link GitToolResult}'s ok/err
   * envelope but flattens it to JSON-friendly fields so the UI can render the per-repo list.
   */
  public record ApplyRepoOutcome(
      String repoFullName,
      String branch,
      boolean ok,
      boolean clean,
      String message,
      String code,
      String blockingGate,
      boolean retryable,
      String remediation) {

    /** Source-compatible constructor for successful/legacy callers. */
    public ApplyRepoOutcome(
        String repoFullName, String branch, boolean ok, boolean clean, String message) {
      this(
          repoFullName,
          branch,
          ok,
          clean,
          message,
          ok ? (clean ? "SCM_APPLY_CLEAN" : "SCM_APPLY_PUSH_SUCCEEDED") : "SCM_APPLY_PUSH_FAILED",
          ok ? null : "GIT_PUSH",
          !ok,
          ok ? null : "Resolve the reported Git push failure, then retry Apply to Run.");
    }

    static ApplyRepoOutcome blocked(
        String repoFullName,
        String branch,
        String message,
        String code,
        String blockingGate,
        boolean retryable,
        String remediation) {
      return new ApplyRepoOutcome(
          repoFullName, branch, false, false, message, code, blockingGate, retryable, remediation);
    }
  }

  /**
   * Aggregate outcome for an apply-suggestion push across all linked repos.
   *
   * @param mode {@code MULTI_REPO} | {@code SINGLE_REPO} | {@code SKIPPED}
   * @param skippedReason populated only when {@code mode == SKIPPED} (e.g. "no PAT", "no repo")
   * @param perRepo per-repo outcomes when {@code mode == MULTI_REPO} or single-element list when
   *     {@code SINGLE_REPO}
   */
  public record ApplyPushResult(
      String mode,
      String skippedReason,
      List<ApplyRepoOutcome> perRepo,
      String code,
      String blockingGate,
      boolean publicationEligible,
      boolean retryable,
      String remediation) {

    public static ApplyPushResult skipped(String reason) {
      return new ApplyPushResult(
          "SKIPPED",
          reason,
          List.of(),
          "SCM_APPLY_SKIPPED",
          "SCM_CONFIGURATION",
          false,
          true,
          "Correct the reported configuration problem, then retry the apply push.");
    }

    /**
     * Fail-closed publication result. Mode remains {@code SKIPPED} for compatibility with the
     * existing apply summary renderer, while the additive fields preserve the exact gate, stable
     * code, eligibility state, retry policy, and remediation for structured clients.
     */
    public static ApplyPushResult blocked(
        String code, String blockingGate, String reason, boolean retryable, String remediation) {
      String actionableReason =
          StringUtils.hasText(remediation) ? reason + " — " + remediation : reason;
      return new ApplyPushResult(
          "SKIPPED",
          actionableReason,
          List.of(),
          code,
          blockingGate,
          false,
          retryable,
          remediation);
    }

    public static ApplyPushResult multiRepo(List<ApplyRepoOutcome> outcomes) {
      return aggregate("MULTI_REPO", outcomes);
    }

    public static ApplyPushResult singleRepo(ApplyRepoOutcome outcome) {
      return aggregate("SINGLE_REPO", List.of(outcome));
    }

    private static ApplyPushResult aggregate(String mode, List<ApplyRepoOutcome> outcomes) {
      List<ApplyRepoOutcome> safe = outcomes == null ? List.of() : List.copyOf(outcomes);
      long failed = safe.stream().filter(outcome -> !outcome.ok()).count();
      if (safe.isEmpty()) {
        return new ApplyPushResult(
            mode,
            null,
            safe,
            "SCM_APPLY_NO_REPOSITORIES_RESOLVED",
            "REPOSITORY_RESOLUTION",
            false,
            true,
            "Refresh the linked repositories and retry Apply to Run.");
      }
      if (failed == 0) {
        return new ApplyPushResult(
            mode, null, safe, "SCM_APPLY_PUSH_SUCCEEDED", null, true, false, null);
      }
      boolean partial = failed < safe.size();
      java.util.Set<String> failureGates =
          safe.stream()
              .filter(outcome -> !outcome.ok())
              .map(ApplyRepoOutcome::blockingGate)
              .filter(java.util.Objects::nonNull)
              .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
      String aggregateGate = failureGates.size() == 1 ? failureGates.iterator().next() : "MULTIPLE";
      return new ApplyPushResult(
          mode,
          null,
          safe,
          partial ? "SCM_APPLY_PUSH_PARTIAL" : "SCM_APPLY_PUSH_FAILED",
          aggregateGate,
          false,
          safe.stream().filter(outcome -> !outcome.ok()).anyMatch(ApplyRepoOutcome::retryable),
          partial
              ? "Some repositories were published and some failed. Resolve each reported failure"
                  + " before retrying; already-published repositories may be clean on retry."
              : "Resolve the per-repository publication failures, then retry Apply to Run.");
    }
  }

  /**
   * Push apply-suggestion changes to each repo's existing PR branch (or {@code
   * executespec/<runIdShort>} when no PR is open). Uses {@link GitTool#pushToExistingBranch} —
   * fast-forward only, no force-push, no PR creation. The existing PR (if any) picks up the new
   * commit automatically; no new PR is opened.
   *
   * <p>Why this is a separate method from {@link #pushMultiRepo}: that path is the run-end push +
   * PR-create flow (force-push to a fresh branch, open PR, cross-link bodies). Apply lives on top
   * of an existing run + existing PR; force-push would obliterate intermediate review-driven
   * commits and reopening a closed PR is a different decision the user might not want.
   *
   * <p>Diagnosed from run a076c876 apply attempt (2026-05-03): {@code ApplyService.tryGitCommit}
   * shelled {@code git add} + {@code git commit} at the WORKSPACE root, but each {@code
   * repos/<name>/} contains its OWN nested {@code .git} that the wrapping git treats as a submodule
   * (gitlink). The outer commit recorded only the submodule SHAs; the inner repo working trees
   * stayed dirty, were never pushed, and never reached GitHub. Apply was effectively a no-op for
   * the user.
   */
  public ApplyPushResult pushApplyChanges(PipelineRun run, String commitMessage) {
    if (run == null || run.getRunId() == null) {
      return ApplyPushResult.blocked(
          "SCM_APPLY_RUN_MISSING",
          "RUN",
          "Apply to Run requires a durable run identity",
          false,
          "Reload the run. If it no longer exists, start a new run.");
    }

    // Apply-to-run writes to an existing PR branch and is therefore a publication boundary, not
    // merely a local chat mutation. Run the same freshness/build/test/workflow/exact-route
    // admission used by the canonical push/PR and deployment paths before credential lookup or
    // any Git/SCM operation.
    PublicationAdmissionService.Admission admission;
    try {
      admission = publicationAdmissionService.assertEligible(run);
    } catch (PublicationAdmissionService.PublicationBlockedException ineligible) {
      PublicationAdmissionService.Eligibility eligibility = ineligible.eligibility();
      log.warn(
          "[{}] apply publication blocked by {}: {}",
          run.getRunId(),
          ineligible.code(),
          ineligible.getMessage());
      return ApplyPushResult.blocked(
          ineligible.code(),
          eligibility.blockingGate(),
          ineligible.getMessage(),
          eligibility.retryable(),
          eligibility.remediation());
    }
    try (admission) {
      admission.assertOwned();

      // Persist the exact protected-contract comparison admitted at the mutation boundary. The
      // read-only compatibility evaluation inside PublicationAdmissionService is necessary for
      // eligibility previews, but cannot replace this final fail-closed evidence write.
      try {
        boolean brownfield =
            "EXISTING".equalsIgnoreCase(String.valueOf(run.getRepoMode()))
                || StringUtils.hasText(run.getSelectedRepoIds())
                || brownfieldHttpContractGuard.hasBaseline(run.getRunId());
        if (brownfield) brownfieldHttpContractGuard.assertCompatible(run.getRunId());
      } catch (BrownfieldHttpContractGuard.ContractCompatibilityException incompatible) {
        log.warn(
            "[{}] apply publication blocked by {}: {}",
            run.getRunId(),
            incompatible.code(),
            incompatible.getMessage());
        return ApplyPushResult.blocked(
            incompatible.code(),
            "PROTECTED_CONTRACTS",
            incompatible.getMessage(),
            false,
            contractRetryGuidance(incompatible.code()));
      }

      AppUser user =
          run.getCreatedByUserId() != null
              ? appUserRepository.findById(run.getCreatedByUserId()).orElse(null)
              : null;
      String pat = user != null ? user.getGithubPat() : null;
      if (!StringUtils.hasText(pat)) {
        return ApplyPushResult.blocked(
            "SCM_APPLY_CREDENTIAL_MISSING",
            "AUTHENTICATION",
            "The run owner has no GitHub credential",
            true,
            "Connect GitHub or add a valid personal access token, then retry Apply to Run.");
      }

      List<java.util.UUID> repoIds = parseRepoIds(run.getSelectedRepoIds());

      // Multi-repo path — each linked repo has its own clone under repos/<name>/.
      if (!repoIds.isEmpty()) {
        Project project =
            run.getProjectId() != null
                ? projectRepository.findById(run.getProjectId()).orElse(null)
                : null;
        String fallbackBranch = resolveBranchName(project, run);

        // Index existing PR records for this run so we can prefer their headBranch.
        java.util.Map<String, String> repoToBranch = new java.util.HashMap<>();
        for (PullRequestRecord rec :
            pullRequestRecordRepository.findByRunIdOrderByCreatedAtAsc(run.getRunId())) {
          if (rec.getStatus() == PullRequestRecord.Status.MERGED
              || rec.getStatus() == PullRequestRecord.Status.CLOSED) continue;
          if (rec.getRepoFullName() != null && rec.getHeadBranch() != null) {
            repoToBranch.put(rec.getRepoFullName(), rec.getHeadBranch());
          }
        }

        List<ApplyRepoOutcome> outcomes = new java.util.ArrayList<>();
        for (java.util.UUID id : repoIds) {
          ProjectRepo repo = projectRepoRepository.findById(id).orElse(null);
          if (repo == null) {
            outcomes.add(
                ApplyRepoOutcome.blocked(
                    "repository-id:" + id,
                    fallbackBranch,
                    "Selected repository could not be resolved",
                    "SCM_APPLY_REPOSITORY_NOT_FOUND",
                    "REPOSITORY_RESOLUTION",
                    true,
                    "Refresh the project repository links, then retry Apply to Run."));
            continue;
          }
          String repoFullName = repo.getGithubRepoFullName();
          String subPath = "repos/" + lastPathSegment(repoFullName);
          String remote = "https://github.com/" + repoFullName;
          String branchName = repoToBranch.getOrDefault(repoFullName, fallbackBranch);

          try {
            publicationGuard.assertGitWorkspaceSafe(run.getRunId(), subPath);
          } catch (Exception unsafe) {
            outcomes.add(
                ApplyRepoOutcome.blocked(
                    repoFullName,
                    branchName,
                    unsafe.getMessage(),
                    "SCM_APPLY_WORKSPACE_UNSAFE",
                    "WORKSPACE_SAFETY",
                    false,
                    "Remove the blocked artifact or repair the workspace, regenerate validation,"
                        + " then retry Apply to Run."));
            continue;
          }

          GitToolResult res =
              gitTool.pushToExistingBranch(
                  run.getRunId(), subPath, remote, pat, branchName, commitMessage);
          boolean ok = res.isOk();
          boolean clean = ok && "CLEAN".equals(res.value());
          outcomes.add(
              ok
                  ? new ApplyRepoOutcome(repoFullName, branchName, true, clean, res.value())
                  : ApplyRepoOutcome.blocked(
                      repoFullName,
                      branchName,
                      res.value(),
                      "SCM_APPLY_GIT_PUSH_FAILED",
                      "GIT_PUSH",
                      true,
                      "Resolve authentication, branch protection, or non-fast-forward conflicts,"
                          + " then retry Apply to Run."));
          if (!ok) {
            log.warn(
                "[{}] apply push failed for {} on branch {}: {}",
                run.getRunId(),
                repoFullName,
                branchName,
                res.value());
          }
        }
        return ApplyPushResult.multiRepo(outcomes);
      }

      // Single-repo path — workspace root IS the repo (cloned from run.repoUrl).
      if (StringUtils.hasText(run.getRepoUrl())) {
        String branch = "executespec/" + run.getRunId().substring(0, 8);
        // Prefer existing PR branch when available.
        for (PullRequestRecord rec :
            pullRequestRecordRepository.findByRunIdOrderByCreatedAtAsc(run.getRunId())) {
          if (rec.getStatus() == PullRequestRecord.Status.MERGED
              || rec.getStatus() == PullRequestRecord.Status.CLOSED) continue;
          if (StringUtils.hasText(rec.getHeadBranch())) {
            branch = rec.getHeadBranch();
            break;
          }
        }
        try {
          publicationGuard.assertGitWorkspaceSafe(run.getRunId());
        } catch (Exception unsafe) {
          return ApplyPushResult.singleRepo(
              ApplyRepoOutcome.blocked(
                  repoFullNameFromUrl(run.getRepoUrl()),
                  branch,
                  unsafe.getMessage(),
                  "SCM_APPLY_WORKSPACE_UNSAFE",
                  "WORKSPACE_SAFETY",
                  false,
                  "Remove the blocked artifact or repair the workspace, regenerate validation, then"
                      + " retry Apply to Run."));
        }
        GitToolResult res =
            gitTool.pushToExistingBranch(
                run.getRunId(), ".", run.getRepoUrl(), pat, branch, commitMessage);
        String repoName = repoFullNameFromUrl(run.getRepoUrl());
        ApplyRepoOutcome outcome =
            res.isOk()
                ? new ApplyRepoOutcome(
                    repoName, branch, true, "CLEAN".equals(res.value()), res.value())
                : ApplyRepoOutcome.blocked(
                    repoName,
                    branch,
                    res.value(),
                    "SCM_APPLY_GIT_PUSH_FAILED",
                    "GIT_PUSH",
                    true,
                    "Resolve authentication, branch protection, or non-fast-forward conflicts, then"
                        + " retry Apply to Run.");
        return ApplyPushResult.singleRepo(outcome);
      }

      return ApplyPushResult.blocked(
          "SCM_APPLY_REPOSITORY_MISSING",
          "REPOSITORY_RESOLUTION",
          "No publishable repository is configured for this run",
          false,
          "Link a repository and start a repository-backed run before using Apply to Run.");
    }
  }
}
