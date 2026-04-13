package com.navneet.executespec.integration.scm;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.repo.BitbucketRepoService;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.tool.GitTool;
import com.navneet.executespec.tool.ToolResult;
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
            String branchName = "executespec/" + run.getRunId().substring(0, 8);
            ToolResult result = gitTool.pushToBranch(run.getRunId(), remote, pat, branchName);
            if (!result.isOk()) {
                log.warn("GitHub branch push failed for run {}: {}", run.getRunId(), result.value());
                return;
            }
            String defaultBranch = gitHubRepoService.getDefaultBranch(remote, pat);
            String prTitle = run.getRunTitle() != null ? run.getRunTitle() : "ExecuteSpec changes";
            String prBody  = run.getRunSummary() != null ? run.getRunSummary() : run.getUserRequest();
            String prUrl = gitHubRepoService.createPullRequest(remote, branchName, defaultBranch,
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
            }
            gitHubRepoService.updateRepoDescription(remote, run.getUserRequest(), pat);
        } else {
            ToolResult result = gitTool.pushToRemote(run.getRunId(), remote, pat);
            if (!result.isOk()) {
                log.warn("GitHub push failed for run {}: {}", run.getRunId(), result.value());
            }
        }
    }
}
