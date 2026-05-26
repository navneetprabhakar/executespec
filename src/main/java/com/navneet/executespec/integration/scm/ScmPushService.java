package com.navneet.executespec.integration.scm;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.pr.PullRequestRecord;
import com.navneet.executespec.project.pr.PullRequestRecordRepository;
import com.navneet.executespec.project.pr.PullRequestRecordService;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.BitbucketRepoService;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
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
 * <p>Decides between Bitbucket and GitHub based on credentials present on the run / user, creates a
 * feature branch (or new repo), pushes the workspace, and opens a pull request. Used by the {@code
 * /api/runs/{id}/push-scm} and legacy {@code /push-github} endpoints.
 *
 * <p>Harness-agnostic — lives in the {@code integration.scm} package so it survives V1 deletion.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScmPushService {

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
  // RL-2026-05-041 (audit #16) — enqueue orphan-branch cleanup when a push succeeds but PR fails.
  private final com.navneet.executespec.saga.SideEffectOutbox sideEffectOutbox;

  /**
   * Entry point for manual SCM push from {@code RunController}. Resolves the user creds off the
   * run's creator, then delegates.
   */
  public void push(PipelineRun run) {
    AppUser scmUser =
        run.getCreatedByUserId() != null
            ? appUserRepository.findById(run.getCreatedByUserId()).orElse(null)
            : null;
    pushIfConfigured(run, scmUser);
  }

  /** Dispatch to Bitbucket or GitHub based on credentials. */
  private void pushIfConfigured(PipelineRun run, AppUser user) {
    if (StringUtils.hasText(run.getBitbucketUsername())
        && StringUtils.hasText(run.getBitbucketWorkspace())) {
      pushToBitbucket(run);
      return;
    }
    if (user != null
        && StringUtils.hasText(user.getBitbucketUsername())
        && StringUtils.hasText(user.getBitbucketWorkspace())) {
      run.setBitbucketUsername(user.getBitbucketUsername());
      run.setBitbucketAppPassword(user.getBitbucketAppPassword());
      run.setBitbucketWorkspace(user.getBitbucketWorkspace());
      run.setBitbucketRepoSlug(user.getBitbucketRepoSlug());
      run.setScmProvider("BITBUCKET");
      pushToBitbucket(run);
      return;
    }
    pushToGitHubIfConfigured(run, user);
  }

  private void pushToBitbucket(PipelineRun run) {
    String workspace = run.getBitbucketWorkspace();
    String repoSlug = run.getBitbucketRepoSlug();
    String username = run.getBitbucketUsername();
    String appPassword = run.getBitbucketAppPassword();

    if (!StringUtils.hasText(repoSlug)) {
      log.warn(
          "[{}] Bitbucket workspace set but repo slug missing — skipping push", run.getRunId());
      return;
    }

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

      gitTool.pushToBranchBitbucket(run.getRunId(), cloneUrl, username, appPassword, branchName);

      String title =
          StringUtils.hasText(run.getRunTitle()) ? run.getRunTitle() : "ExecuteSpec changes";
      String body =
          StringUtils.hasText(run.getRunSummary()) ? run.getRunSummary() : run.getUserRequest();
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
      }
    } catch (Exception e) {
      log.warn("[{}] Bitbucket push failed: {}", run.getRunId(), e.getMessage());
    }
  }

  private void pushToGitHubIfConfigured(PipelineRun run, AppUser user) {
    // Pilot v3.0 — multi-repo runs: push each cloned subdirectory as its own
    // branch + PR. Falls through to the single-repo path if no project repos
    // were selected.
    if (StringUtils.hasText(run.getSelectedRepoIds())) {
      String pat = resolvePat(run, user);
      if (pat == null || pat.isBlank()) {
        log.warn("Multi-repo push requires a PAT — skipping for run {}", run.getRunId());
        return;
      }
      pushMultiRepo(run, pat);
      return;
    }

    String repoMode = run.getRepoMode();
    String repoUrl = run.getRepoUrl();

    if ((repoMode == null || "NONE".equals(repoMode)) && repoUrl == null) return;

    String pat = resolvePat(run, user);
    String owner =
        (run.getGithubOwner() != null && !run.getGithubOwner().isBlank())
            ? run.getGithubOwner()
            : (props.getGithub() != null ? props.getGithub().getDefaultOwner() : null);

    if (pat == null || pat.isBlank()) {
      log.warn("GitHub PAT not configured — skipping push for run {}", run.getRunId());
      return;
    }

    String remote = repoUrl;
    if ("NEW".equals(repoMode) && (repoUrl == null || repoUrl.isBlank())) {
      log.info("Creating new GitHub repo for run {} (owner={})", run.getRunId(), owner);
      remote = gitHubRepoService.createRepo(run.getUserRequest(), null, "private", pat, owner);
      if (remote != null) {
        PipelineRun freshRun = stateStore.findRun(run.getRunId()).orElse(run);
        freshRun.setRepoUrl(remote);
        stateStore.save(freshRun);
        run.setRepoUrl(remote);
        log.info("Created GitHub repo: {}", remote);
      }
    }

    if (remote == null || remote.isBlank()) {
      log.warn("No remote URL available — skipping push for run {}", run.getRunId());
      return;
    }

    log.info("Pushing workspace to GitHub: {}", remote);

    if ("EXISTING".equals(repoMode)) {
      // Pilot R5 — project settings may override branch naming / target branch / reviewers.
      Project project =
          run.getProjectId() != null
              ? projectRepository.findById(run.getProjectId()).orElse(null)
              : null;
      String branchName = resolveBranchName(project, run);
      GitToolResult result = gitTool.pushToBranch(run.getRunId(), remote, pat, branchName);
      if (!result.isOk()) {
        log.warn("GitHub branch push failed for run {}: {}", run.getRunId(), result.value());
        return;
      }
      String targetBranch =
          (project != null && StringUtils.hasText(project.getTargetBranch()))
              ? project.getTargetBranch()
              : gitHubRepoService.getDefaultBranch(remote, pat);
      String prTitle = run.getRunTitle() != null ? run.getRunTitle() : "ExecuteSpec changes";
      String prBody = run.getRunSummary() != null ? run.getRunSummary() : run.getUserRequest();
      String prUrl =
          gitHubRepoService.createPullRequest(
              remote, branchName, targetBranch, prTitle, prBody, pat);
      if (prUrl != null) {
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
            run.getRunId(), RunEvent.log(run.getRunId(), "scm", "Pull request created: " + prUrl));

        // Persist a PullRequestRecord so the UI can show multi-PR status
        // and the GitHub webhook can flip it to MERGED/CLOSED later.
        String repoFullName = repoFullNameFromUrl(remote);
        pullRequestRecordService.recordCreated(
            run.getRunId(), null, repoFullName, prUrl, branchName, targetBranch);

        // Request default reviewers from project settings (best-effort).
        List<String> reviewers = parseReviewers(project);
        if (!reviewers.isEmpty()) {
          Integer prNumber =
              com.navneet.executespec.project.pr.PullRequestRecordService.extractPrNumber(prUrl);
          if (prNumber != null) {
            gitHubRepoService.requestReviewers(remote, prNumber, reviewers, pat);
          }
        }
      } else {
        // RL-2026-05-041 (audit #16) — the branch pushed but PR creation failed: it's now an
        // orphan. Enqueue the saga compensation to delete it (PAT resolved from the run owner at
        // execution time — not stored in the payload).
        enqueueOrphanBranchCleanup(run.getRunId(), repoFullNameFromUrl(remote), branchName);
      }
      gitHubRepoService.updateRepoDescription(remote, run.getUserRequest(), pat);
    } else {
      GitToolResult result = gitTool.pushToRemote(run.getRunId(), remote, pat);
      if (!result.isOk()) {
        log.warn("GitHub push failed for run {}: {}", run.getRunId(), result.value());
      }
    }
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
  private void pushMultiRepo(PipelineRun run, String pat) {
    Project project =
        run.getProjectId() != null
            ? projectRepository.findById(run.getProjectId()).orElse(null)
            : null;
    List<java.util.UUID> ids = parseRepoIds(run.getSelectedRepoIds());
    if (ids.isEmpty()) return;

    String branchName = resolveBranchName(project, run);
    String runTitle =
        StringUtils.hasText(run.getRunTitle()) ? run.getRunTitle() : "ExecuteSpec changes";
    String prTitle = runTitle;
    String prBodyBase =
        StringUtils.hasText(run.getRunSummary()) ? run.getRunSummary() : run.getUserRequest();
    List<String> reviewers = parseReviewers(project);

    record Created(ProjectRepo repo, String prUrl, Integer prNumber) {}
    List<Created> created = new java.util.ArrayList<>();

    for (java.util.UUID repoId : ids) {
      ProjectRepo repo = projectRepoRepository.findById(repoId).orElse(null);
      if (repo == null) continue;
      String subPath = "repos/" + lastPathSegment(repo.getGithubRepoFullName());
      String remote = "https://github.com/" + repo.getGithubRepoFullName();

      // 2026-05-02 — Per-repo commit messages (user ask): the message body is the file list
      // for THIS repo only, plus a Co-Authored-By trailer crediting admin@executespec.ai. Avoids
      // the previous behaviour where every repo got the same generic "ExecuteSpec changes" /
      // run-title message regardless of what actually changed in that repo.
      String commitMsg = buildPerRepoCommitMessage(run, runTitle, repo, subPath);

      var pushResult =
          gitTool.commitAndPushSubdir(run.getRunId(), subPath, remote, pat, branchName, commitMsg);
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
        continue;
      }
      if ("CLEAN".equals(pushResult.value())) {
        log.info(
            "[{}] no changes for {}, skipping PR", run.getRunId(), repo.getGithubRepoFullName());
        continue;
      }

      String targetBranch =
          (project != null && StringUtils.hasText(project.getTargetBranch()))
              ? project.getTargetBranch()
              : (repo.getDefaultBranch() != null ? repo.getDefaultBranch() : "main");
      String prUrl =
          gitHubRepoService.createPullRequest(
              remote, branchName, targetBranch, prTitle, prBodyBase, pat);
      if (prUrl == null) {
        // RL-2026-05-041 (audit #16) — branch pushed but PR creation failed → orphan branch.
        enqueueOrphanBranchCleanup(run.getRunId(), repo.getGithubRepoFullName(), branchName);
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

  // ─── Apply-suggestion push (Fix L, 2026-05-03) ──────────────────────────

  /**
   * Per-repo outcome for an apply-time push. Mirrors the shape of {@link GitToolResult}'s ok/err
   * envelope but flattens it to JSON-friendly fields so the UI can render the per-repo list.
   */
  public record ApplyRepoOutcome(
      String repoFullName, String branch, boolean ok, boolean clean, String message) {}

  /**
   * Aggregate outcome for an apply-suggestion push across all linked repos.
   *
   * @param mode {@code MULTI_REPO} | {@code SINGLE_REPO} | {@code SKIPPED}
   * @param skippedReason populated only when {@code mode == SKIPPED} (e.g. "no PAT", "no repo")
   * @param perRepo per-repo outcomes when {@code mode == MULTI_REPO} or single-element list when
   *     {@code SINGLE_REPO}
   */
  public record ApplyPushResult(String mode, String skippedReason, List<ApplyRepoOutcome> perRepo) {

    public static ApplyPushResult skipped(String reason) {
      return new ApplyPushResult("SKIPPED", reason, List.of());
    }

    public static ApplyPushResult multiRepo(List<ApplyRepoOutcome> outcomes) {
      return new ApplyPushResult("MULTI_REPO", null, outcomes);
    }

    public static ApplyPushResult singleRepo(ApplyRepoOutcome outcome) {
      return new ApplyPushResult("SINGLE_REPO", null, List.of(outcome));
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
      return ApplyPushResult.skipped("missing run");
    }

    AppUser user =
        run.getCreatedByUserId() != null
            ? appUserRepository.findById(run.getCreatedByUserId()).orElse(null)
            : null;
    String pat = user != null ? user.getGithubPat() : null;
    if (!StringUtils.hasText(pat)) {
      return ApplyPushResult.skipped("no GitHub PAT on run owner");
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
        if (repo == null) continue;
        String repoFullName = repo.getGithubRepoFullName();
        String subPath = "repos/" + lastPathSegment(repoFullName);
        String remote = "https://github.com/" + repoFullName;
        String branchName = repoToBranch.getOrDefault(repoFullName, fallbackBranch);

        GitToolResult res =
            gitTool.pushToExistingBranch(
                run.getRunId(), subPath, remote, pat, branchName, commitMessage);
        boolean ok = res.isOk();
        boolean clean = ok && "CLEAN".equals(res.value());
        outcomes.add(new ApplyRepoOutcome(repoFullName, branchName, ok, clean, res.value()));
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
      GitToolResult res =
          gitTool.pushToExistingBranch(
              run.getRunId(), ".", run.getRepoUrl(), pat, branch, commitMessage);
      String repoName = repoFullNameFromUrl(run.getRepoUrl());
      ApplyRepoOutcome outcome =
          new ApplyRepoOutcome(
              repoName, branch, res.isOk(), res.isOk() && "CLEAN".equals(res.value()), res.value());
      return ApplyPushResult.singleRepo(outcome);
    }

    return ApplyPushResult.skipped("no repo configured (scratch workspace)");
  }
}
