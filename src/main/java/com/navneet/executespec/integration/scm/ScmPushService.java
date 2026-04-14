package com.navneet.executespec.integration.scm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.pr.PullRequestRecord;
import com.navneet.executespec.project.pr.PullRequestRecordService;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.repo.BitbucketRepoService;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.tool.GitTool;
import com.navneet.executespec.tool.ToolResult;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * SCM push orchestration extracted from V1 {@code PipelineHarness}.
 *
 * <p>Decides between Bitbucket and GitHub based on credentials present on the
 * run / user, creates a feature branch (or new repo), pushes the workspace,
 * and opens a pull request. Used by the {@code /api/runs/{id}/push-scm} and
 * legacy {@code /push-github} endpoints.
 *
 * <p>Harness-agnostic — lives in the {@code integration.scm} package so it
 * survives V1 deletion.
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
    private final ProjectRepoRepository projectRepoRepository;
    private final ObjectMapper objectMapper;

    /**
     * Entry point for manual SCM push from {@code RunController}. Resolves the
     * user creds off the run's creator, then delegates.
     */
    public void push(PipelineRun run) {
        AppUser scmUser = run.getCreatedByUserId() != null
                ? appUserRepository.findById(run.getCreatedByUserId()).orElse(null) : null;
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
        pushToGitHubIfConfigured(run);
    }

    private void pushToBitbucket(PipelineRun run) {
        String workspace = run.getBitbucketWorkspace();
        String repoSlug = run.getBitbucketRepoSlug();
        String username = run.getBitbucketUsername();
        String appPassword = run.getBitbucketAppPassword();

        if (!StringUtils.hasText(repoSlug)) {
            log.warn("[{}] Bitbucket workspace set but repo slug missing — skipping push",
                    run.getRunId());
            return;
        }

        try {
            String branchName = "executespec/" + run.getRunId().substring(0, 8);
            String cloneUrl = "https://bitbucket.org/" + workspace + "/" + repoSlug + ".git";

            gitTool.pushToBranchBitbucket(run.getRunId(), cloneUrl, username, appPassword, branchName);

            String title = StringUtils.hasText(run.getRunTitle()) ? run.getRunTitle() : "ExecuteSpec changes";
            String body  = StringUtils.hasText(run.getRunSummary()) ? run.getRunSummary() : run.getUserRequest();
            String prUrl = bitbucketRepoService.createPullRequest(
                    workspace, repoSlug, branchName, title, body, username, appPassword);

            if (prUrl != null) {
                run.setScmPrUrl(prUrl);
                run.setScmProvider("BITBUCKET");
                stateStore.save(run);
                log.info("[{}] Bitbucket PR created: {}", run.getRunId(), prUrl);
                broadcaster.emit(run.getRunId(),
                        RunEvent.log(run.getRunId(), "scm", "Bitbucket pull request created: " + prUrl));
            }
        } catch (Exception e) {
            log.warn("[{}] Bitbucket push failed: {}", run.getRunId(), e.getMessage());
        }
    }

    private void pushToGitHubIfConfigured(PipelineRun run) {
        // Pilot v3.0 — multi-repo runs: push each cloned subdirectory as its own
        // branch + PR. Falls through to the single-repo path if no project repos
        // were selected.
        if (StringUtils.hasText(run.getSelectedRepoIds())) {
            String pat = (run.getGithubPat() != null && !run.getGithubPat().isBlank())
                    ? run.getGithubPat()
                    : (props.getGithub() != null ? props.getGithub().getPat() : null);
            if (pat == null || pat.isBlank()) {
                log.warn("Multi-repo push requires a PAT — skipping for run {}", run.getRunId());
                return;
            }
            pushMultiRepo(run, pat);
            return;
        }

        String repoMode = run.getRepoMode();
        String repoUrl  = run.getRepoUrl();

        if ((repoMode == null || "NONE".equals(repoMode)) && repoUrl == null) return;

        String pat = (run.getGithubPat() != null && !run.getGithubPat().isBlank())
                ? run.getGithubPat()
                : (props.getGithub() != null ? props.getGithub().getPat() : null);
        String owner = (run.getGithubOwner() != null && !run.getGithubOwner().isBlank())
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
            Project project = run.getProjectId() != null
                    ? projectRepository.findById(run.getProjectId()).orElse(null) : null;
            String branchName = resolveBranchName(project, run);
            ToolResult result = gitTool.pushToBranch(run.getRunId(), remote, pat, branchName);
            if (!result.isOk()) {
                log.warn("GitHub branch push failed for run {}: {}", run.getRunId(), result.value());
                return;
            }
            String targetBranch = (project != null && StringUtils.hasText(project.getTargetBranch()))
                    ? project.getTargetBranch()
                    : gitHubRepoService.getDefaultBranch(remote, pat);
            String prTitle = run.getRunTitle() != null ? run.getRunTitle() : "ExecuteSpec changes";
            String prBody  = run.getRunSummary() != null ? run.getRunSummary() : run.getUserRequest();
            String prUrl = gitHubRepoService.createPullRequest(remote, branchName, targetBranch,
                    prTitle, prBody, pat);
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
                broadcaster.emit(run.getRunId(),
                        RunEvent.log(run.getRunId(), "scm", "Pull request created: " + prUrl));

                // Persist a PullRequestRecord so the UI can show multi-PR status
                // and the GitHub webhook can flip it to MERGED/CLOSED later.
                String repoFullName = repoFullNameFromUrl(remote);
                pullRequestRecordService.recordCreated(
                        run.getRunId(), null, repoFullName, prUrl, branchName, targetBranch);

                // Request default reviewers from project settings (best-effort).
                List<String> reviewers = parseReviewers(project);
                if (!reviewers.isEmpty()) {
                    Integer prNumber = com.navneet.executespec.project.pr.PullRequestRecordService
                            .extractPrNumber(prUrl);
                    if (prNumber != null) {
                        gitHubRepoService.requestReviewers(remote, prNumber, reviewers, pat);
                    }
                }
            }
            gitHubRepoService.updateRepoDescription(remote, run.getUserRequest(), pat);
        } else {
            ToolResult result = gitTool.pushToRemote(run.getRunId(), remote, pat);
            if (!result.isOk()) {
                log.warn("GitHub push failed for run {}: {}", run.getRunId(), result.value());
            }
        }
    }

    /**
     * Resolve the push branch name from {@link Project#getBranchNamingPattern()}
     * (supporting {@code {runId}} and {@code {description}} placeholders) or the
     * legacy {@code executespec/<runId-short>} default.
     */
    private static String resolveBranchName(Project project, PipelineRun run) {
        String pattern = project != null ? project.getBranchNamingPattern() : null;
        String shortId = run.getRunId().substring(0, Math.min(8, run.getRunId().length()));
        if (!StringUtils.hasText(pattern)) {
            return "executespec/" + shortId;
        }
        String description = slugify(run.getRunTitle() != null ? run.getRunTitle() : run.getUserRequest());
        return pattern
                .replace("{runId}", shortId)
                .replace("{description}", description);
    }

    /** Converts a free-text title into a git-safe slug (≤40 chars). */
    private static String slugify(String text) {
        if (text == null) return "change";
        String slug = text.toLowerCase().replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
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
     * Pilot v3.0 — push each linked repo's cloned subdirectory under
     * {@code workspace/<runId>/repos/<name>/} to its own branch + open a PR.
     * Cross-links the PRs by patching each body to reference the others.
     * Failure of one repo does not abort the others.
     */
    private void pushMultiRepo(PipelineRun run, String pat) {
        Project project = run.getProjectId() != null
                ? projectRepository.findById(run.getProjectId()).orElse(null) : null;
        List<java.util.UUID> ids = parseRepoIds(run.getSelectedRepoIds());
        if (ids.isEmpty()) return;

        String branchName = resolveBranchName(project, run);
        String commitMsg = StringUtils.hasText(run.getRunTitle())
                ? run.getRunTitle() : "ExecuteSpec changes";
        String prTitle = commitMsg;
        String prBodyBase = StringUtils.hasText(run.getRunSummary())
                ? run.getRunSummary() : run.getUserRequest();
        List<String> reviewers = parseReviewers(project);

        record Created(ProjectRepo repo, String prUrl, Integer prNumber) {}
        List<Created> created = new java.util.ArrayList<>();

        for (java.util.UUID repoId : ids) {
            ProjectRepo repo = projectRepoRepository.findById(repoId).orElse(null);
            if (repo == null) continue;
            String subPath = "repos/" + lastPathSegment(repo.getGithubRepoFullName());
            String remote = "https://github.com/" + repo.getGithubRepoFullName();

            var pushResult = gitTool.commitAndPushSubdir(
                    run.getRunId(), subPath, remote, pat, branchName, commitMsg);
            if (!pushResult.isOk()) {
                log.warn("[{}] multi-repo push failed for {}: {}",
                        run.getRunId(), repo.getGithubRepoFullName(), pushResult.value());
                broadcaster.emit(run.getRunId(),
                        RunEvent.log(run.getRunId(), "scm",
                                "Push failed for " + repo.getGithubRepoFullName() + ": " + pushResult.value()));
                continue;
            }
            if ("CLEAN".equals(pushResult.value())) {
                log.info("[{}] no changes for {}, skipping PR", run.getRunId(), repo.getGithubRepoFullName());
                continue;
            }

            String targetBranch = (project != null && StringUtils.hasText(project.getTargetBranch()))
                    ? project.getTargetBranch()
                    : (repo.getDefaultBranch() != null ? repo.getDefaultBranch() : "main");
            String prUrl = gitHubRepoService.createPullRequest(
                    remote, branchName, targetBranch, prTitle, prBodyBase, pat);
            if (prUrl == null) continue;

            Integer prNumber = PullRequestRecordService.extractPrNumber(prUrl);
            PullRequestRecord record = pullRequestRecordService.recordCreated(
                    run.getRunId(), repo.getId(), repo.getGithubRepoFullName(),
                    prUrl, branchName, targetBranch);
            if (record != null) created.add(new Created(repo, prUrl, prNumber));

            if (!reviewers.isEmpty() && prNumber != null) {
                gitHubRepoService.requestReviewers(remote, prNumber, reviewers, pat);
            }
            broadcaster.emit(run.getRunId(),
                    RunEvent.log(run.getRunId(), "scm",
                            "PR opened for " + repo.getGithubRepoFullName() + ": " + prUrl));
        }

        // Cross-link: patch each PR body with the list of sibling PRs.
        if (created.size() > 1) {
            String siblingList = created.stream()
                    .map(c -> "- " + c.repo().getGithubRepoFullName() + ": " + c.prUrl())
                    .collect(java.util.stream.Collectors.joining("\n"));
            for (Created c : created) {
                if (c.prNumber() == null) continue;
                String body = (prBodyBase == null ? "" : prBodyBase)
                        + "\n\n---\n**Multi-repo change** — sibling PRs:\n" + siblingList;
                gitHubRepoService.updatePullRequestBody(
                        "https://github.com/" + c.repo().getGithubRepoFullName(),
                        c.prNumber(), body, pat);
            }
        }
    }

    private List<java.util.UUID> parseRepoIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json,
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
            return objectMapper.readValue(project.getDefaultReviewers(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            log.warn("Failed to parse project.defaultReviewers: {}", e.getMessage());
            return List.of();
        }
    }
}
