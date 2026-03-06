package com.navneet.agentteam.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

@Component
@Slf4j
public class GitTool {

    @Value("${agent.workspace}")
    private String workspaceRoot;

    /**
     * Opens the git repo for this task's working directory.
     * If a worktree override is active (set by WorkspaceContext), opens that path;
     * otherwise falls back to the shared workspace root.
     */
    private Git openRepo(String runId) throws IOException {
        String override = WorkspaceContext.getWorkDir(runId);
        if (override != null) return Git.open(Path.of(override).toFile());
        return Git.open(Path.of(workspaceRoot, runId).toFile());
    }

    // ── Worktree lifecycle (called by WorkerAgent, not exposed as @Tool) ──────

    /**
     * Creates a git worktree for a task so it gets its own isolated working
     * directory. The worktree is branched from 'main' and dependency branches
     * are merged in before the agentic loop starts.
     *
     * @return absolute path to the worktree directory, or the repo root on failure
     */
    public String createWorktree(String runId, String taskId, String branchName,
                                  java.util.List<String> depBranches) {
        Path repoRoot = Path.of(workspaceRoot, runId).toAbsolutePath().normalize();
        Path worktreeDir = repoRoot.resolve("worktrees").resolve(taskId);
        try {
            // On retry: reset worktree to clean state before reuse
            if (java.nio.file.Files.exists(worktreeDir.resolve(".git"))) {
                log.info("[GitTool] Worktree '{}' already exists — resetting for retry", taskId);
                // Reset tracked files to branch HEAD
                new ProcessBuilder("git", "checkout", ".")
                        .directory(worktreeDir.toFile())
                        .redirectErrorStream(true)
                        .start().waitFor();
                // Remove untracked files and directories
                new ProcessBuilder("git", "clean", "-fd")
                        .directory(worktreeDir.toFile())
                        .redirectErrorStream(true)
                        .start().waitFor();
                // Re-merge dependency branches (may have been updated by retried deps)
                if (depBranches != null && !depBranches.isEmpty()) {
                    for (String dep : depBranches) {
                        ProcessBuilder mpb = new ProcessBuilder(
                                "git", "merge", "--no-edit", "-X", "theirs", dep);
                        mpb.directory(worktreeDir.toFile());
                        mpb.redirectErrorStream(true);
                        Process mp = mpb.start();
                        String mout = new String(mp.getInputStream().readAllBytes());
                        int mexit = mp.waitFor();
                        if (mexit != 0) {
                            log.warn("[GitTool] Retry re-merge of '{}' failed in worktree {}: {}",
                                    dep, taskId, mout.trim());
                            new ProcessBuilder("git", "merge", "--abort")
                                    .directory(worktreeDir.toFile())
                                    .redirectErrorStream(true)
                                    .start().waitFor();
                        }
                    }
                }
                return worktreeDir.toString();
            }
            java.nio.file.Files.createDirectories(repoRoot.resolve("worktrees"));

            boolean branchExists;
            try (Git git = Git.open(repoRoot.toFile())) {
                branchExists = git.getRepository().findRef(branchName) != null;
            }

            java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(
                    "git", "worktree", "add", worktreeDir.toString()));
            if (!branchExists) {
                // Create a new branch starting from main's HEAD
                cmd.add("-b"); cmd.add(branchName); cmd.add("main");
            } else {
                // Checkout existing branch — no commit-ish argument allowed here
                cmd.add(branchName);
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(repoRoot.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            int exit = p.waitFor();
            if (exit != 0) {
                log.error("[GitTool] git worktree add failed for '{}': {}", taskId, out);
                return repoRoot.toString(); // fallback
            }
            log.info("[GitTool] Created worktree '{}' at {}", taskId, worktreeDir);

            // Merge dep branches using shell git so that:
            //   1. .gitattributes merge=union is honoured for README.md / CHANGELOG.md
            //      (JGit ignores custom merge drivers, so README conflicts would still occur)
            //   2. -X theirs auto-resolves any remaining code conflicts by preferring the
            //      incoming dep branch — safe because the worktree has no local changes at
            //      this point (ours == main base), so "theirs" = dep's real content.
            if (depBranches != null && !depBranches.isEmpty()) {
                for (String dep : depBranches) {
                    ProcessBuilder mpb = new ProcessBuilder(
                            "git", "merge", "--no-edit", "-X", "theirs", dep);
                    mpb.directory(worktreeDir.toFile());
                    mpb.redirectErrorStream(true);
                    Process mp = mpb.start();
                    String mout = new String(mp.getInputStream().readAllBytes());
                    int mexit = mp.waitFor();
                    if (mexit == 0) {
                        log.info("[GitTool] Worktree {}: merged dep '{}' (shell git -X theirs)", taskId, dep);
                    } else {
                        log.warn("[GitTool] Worktree {}: shell merge of '{}' failed (exit {}): {} — aborting",
                                taskId, dep, mexit, mout.trim());
                        // Attempt abort to restore clean state; non-fatal if already clean
                        new ProcessBuilder("git", "merge", "--abort")
                                .directory(worktreeDir.toFile())
                                .redirectErrorStream(true)
                                .start().waitFor();
                    }
                }
            }
            return worktreeDir.toString();
        } catch (Exception e) {
            log.error("[GitTool] createWorktree failed for '{}': {}", taskId, e.getMessage(), e);
            return repoRoot.toString();
        }
    }

    /** Prunes the git worktree for a task after its branch is merged. Non-fatal. */
    public void removeWorktree(String runId, String taskId) {
        Path repoRoot = Path.of(workspaceRoot, runId).toAbsolutePath().normalize();
        Path worktreeDir = repoRoot.resolve("worktrees").resolve(taskId);
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "worktree", "remove", "--force", worktreeDir.toString());
            pb.directory(repoRoot.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int exit = p.waitFor();
            if (exit == 0) log.info("[GitTool] Removed worktree '{}'", taskId);
            else log.warn("[GitTool] worktree remove exited {} for '{}'", exit, taskId);
        } catch (Exception e) {
            log.warn("[GitTool] removeWorktree failed for '{}': {}", taskId, e.getMessage());
        }
    }

    // ── Worker-safe operations ────────────────────────────────────────────────

    @Tool(description = "Creates and checks out a new git branch in the workspace.")
    public String createBranch(
            @ToolParam(description = "runId", required = true) String runId,
            @ToolParam(description = "Branch name to create", required = true) String branchName) {
        try (Git git = openRepo(runId)) {
            // If branch already exists (e.g. task retry after recovery), just check it out
            // rather than failing with RefAlreadyExistsException.
            boolean exists = git.getRepository().findRef(branchName) != null;
            if (exists) {
                git.checkout().setName(branchName).call();
                log.info("[GitTool] Branch '{}' already exists — checked out (task retry)", branchName);
                return "Checked out existing branch: " + branchName + " (retry)";
            }
            git.checkout().setCreateBranch(true).setName(branchName).call();
            return "Created and checked out branch: " + branchName;
        } catch (RefAlreadyExistsException e) {
            // findRef() missed the branch (race condition or JGit ref-DB quirk after thread
            // interrupt). Fall back to a plain checkout instead of returning an error.
            log.warn("[GitTool] RefAlreadyExistsException for '{}' — findRef missed it; checking out directly",
                    branchName);
            try (Git git2 = openRepo(runId)) {
                git2.checkout().setName(branchName).call();
                log.info("[GitTool] Branch '{}' checked out via fallback path (task retry)", branchName);
                return "Checked out existing branch: " + branchName + " (retry-fallback)";
            } catch (Exception ce) {
                return "Branch creation failed: " + ce.getMessage();
            }
        } catch (Exception e) {
            return "Branch creation failed: " + e.getMessage();
        }
    }

    @Tool(description = "Stage and commit all current changes in the workspace.")
    public String commit(
            @ToolParam(description = "runId", required = true) String runId,
            @ToolParam(description = "Commit message (format: feat(task_id): desc)", required = true) String message) {
        try (Git git = openRepo(runId)) {
            git.add().addFilepattern(".").call();
            RevCommit commit = git.commit().setMessage(message).call();
            return "Committed: " + commit.abbreviate(7).name() + " — " + message;
        } catch (Exception e) {
            return "Commit failed: " + e.getMessage();
        }
    }

    @Tool(description = "Show git working tree status.")
    public String status(
            @ToolParam(description = "runId", required = true) String runId) {
        try (Git git = openRepo(runId)) {
            Status s = git.status().call();
            StringBuilder sb = new StringBuilder();
            if (!s.getAdded().isEmpty())
                sb.append("Added: ").append(s.getAdded()).append("\n");
            if (!s.getModified().isEmpty())
                sb.append("Modified: ").append(s.getModified()).append("\n");
            if (!s.getUntracked().isEmpty())
                sb.append("Untracked: ").append(s.getUntracked()).append("\n");
            return sb.toString().isEmpty() ? "Clean working tree" : sb.toString();
        } catch (Exception e) {
            return "Status failed: " + e.getMessage();
        }
    }

    @Tool(description = "Show git diff of changes since last commit.")
    public String diff(
            @ToolParam(description = "runId", required = true) String runId) {
        try (Git git = openRepo(runId)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            git.diff().setOutputStream(out).call();
            String diff = out.toString();
            return diff.isEmpty() ? "(no changes)" : diff;
        } catch (Exception e) {
            return "Diff failed: " + e.getMessage();
        }
    }

    // ── Worker-safe: merge a dependency branch into the current branch ───────

    /**
     * Merges {@code branchName} into the currently-checked-out branch.
     * Used by WorkerAgent to pull in dependency tasks' committed work before
     * the dependent task starts its agentic loop. Non-fatal: returns an error
     * string rather than throwing, so the worker can still proceed.
     */
    public String mergeBranchIntoCurrent(String runId, String branchName) {
        try (Git git = openRepo(runId)) {
            var ref = git.getRepository().findRef(branchName);
            if (ref == null) {
                log.warn("[GitTool] Cannot merge dependency '{}' — branch not found", branchName);
                return "Branch not found: " + branchName + " (dependency may not have committed yet)";
            }
            MergeResult result = git.merge()
                    .include(ref)
                    .setFastForward(MergeCommand.FastForwardMode.FF)
                    .setCommit(true)
                    .setMessage("chore: merge dependency " + branchName)
                    .call();
            if (result.getMergeStatus().isSuccessful()) {
                log.info("[GitTool] Merged dependency '{}' into current branch", branchName);
                return "Merged dependency: " + branchName;
            } else {
                log.warn("[GitTool] Conflict merging dependency '{}': {}", branchName, result.getMergeStatus());
                return "Merge conflict on " + branchName + ": " + result.getMergeStatus();
            }
        } catch (Exception e) {
            log.warn("[GitTool] Failed to merge dependency branch '{}': {}", branchName, e.getMessage());
            return "Merge failed for " + branchName + ": " + e.getMessage();
        }
    }

    // ── Harness-only operations ───────────────────────────────────────────────

    public String mergeBranchToMain(String runId, String branchName) {
        try (Git git = openRepo(runId)) {
            git.checkout().setName("main").call();
            MergeResult result = git.merge()
                    .include(git.getRepository().findRef(branchName))
                    .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                    .setCommit(true)
                    .setMessage("chore: merge " + branchName)
                    .call();
            if (result.getMergeStatus().isSuccessful()) {
                return "Merged " + branchName + " -> main";
            } else {
                return "ERROR: Merge conflict on " + branchName + ": " + result.getMergeStatus();
            }
        } catch (Exception e) {
            return "ERROR: Merge failed: " + e.getMessage();
        }
    }

    /**
     * Stage and commit any pending changes on the current branch.
     * No-ops silently if the working tree is already clean (nothing to commit).
     * Used by post-merge agents (e.g. ApiTestSuiteAgent) to persist generated
     * files into git so they are not accidentally picked up by a retried worker's
     * {@code git add .}.
     */
    public void commitIfDirty(String runId, String message) {
        try (Git git = openRepo(runId)) {
            Status s = git.status().call();
            boolean dirty = !s.getUntracked().isEmpty()
                         || !s.getModified().isEmpty()
                         || !s.getMissing().isEmpty()
                         || !s.getAdded().isEmpty();
            if (!dirty) return;
            git.add().addFilepattern(".").call();
            git.commit().setMessage(message).call();
            log.debug("commitIfDirty: committed on run {} — {}", runId, message);
        } catch (Exception e) {
            log.warn("commitIfDirty failed for run {}: {}", runId, e.getMessage());
        }
    }

    public String getFullDiff(String runId, String baseBranch, String featureBranch) {
        try (Git git = openRepo(runId)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            git.diff()
                    .setOldTree(getTreeIterator(git, baseBranch))
                    .setNewTree(getTreeIterator(git, featureBranch))
                    .setOutputStream(out).call();
            return out.toString();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Tags the run's workspace repo with "agent-run/{short-id}" after a successful
     * merge.
     * Useful as a lightweight marker; non-fatal on failure.
     */
    public void tagRun(String runId) {
        String shortId = runId.length() >= 8 ? runId.substring(0, 8) : runId;
        String tagName = "agent-run/" + shortId;
        try (Git git = openRepo(runId)) {
            git.tag()
                    .setName(tagName)
                    .setMessage("Agent pipeline run " + runId)
                    .call();
            log.info("[GitTool] Tagged run as '{}'", tagName);
        } catch (Exception e) {
            log.warn("[GitTool] Could not tag run '{}': {}", tagName, e.getMessage());
        }
    }

    /**
     * Aborts an in-progress conflicted merge, restoring the repo to a clean state.
     * Must be called after {@link #mergeBranchToMain} returns an "ERROR" result before
     * attempting the next merge. Non-fatal — logs a warning if the abort fails.
     */
    public void abortMerge(String runId) {
        String wsPath = Path.of(workspaceRoot, runId).toAbsolutePath().normalize().toString();
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "-C", wsPath, "merge", "--abort");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor();
            log.info("[GitTool] Aborted conflicted merge for run {}", runId);
        } catch (Exception e) {
            log.warn("[GitTool] Could not abort merge for run {}: {}", runId, e.getMessage());
        }
    }

    /**
     * Pushes the main branch in the run's workspace to a remote GitHub repository.
     * Non-fatal — returns ToolResult.err() on failure.
     */
    public ToolResult pushToRemote(String runId, String repoUrl, String pat) {
        try {
            String authUrl = repoUrl.replace("https://", "https://oauth2:" + pat + "@");
            String wsPath = Path.of(workspaceRoot, runId).toAbsolutePath().normalize().toString();
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "-C", wsPath, "push", authUrl, "main", "--force");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            int exit = p.waitFor();
            if (exit == 0) {
                log.info("[GitTool] Pushed main to remote: {}", repoUrl);
                return ToolResult.ok("Pushed to " + repoUrl);
            } else {
                log.warn("[GitTool] Push failed (exit {}): {}", exit, output);
                return ToolResult.err("Push failed: " + output);
            }
        } catch (Exception e) {
            log.warn("[GitTool] Push failed: {}", e.getMessage());
            return ToolResult.err("Push failed: " + e.getMessage());
        }
    }

    private AbstractTreeIterator getTreeIterator(Git git, String ref) throws Exception {
        ObjectId id = git.getRepository().resolve(ref + "^{tree}");
        CanonicalTreeParser p = new CanonicalTreeParser();
        try (ObjectReader reader = git.getRepository().newObjectReader()) {
            p.reset(reader, id);
        }
        return p;
    }
}
