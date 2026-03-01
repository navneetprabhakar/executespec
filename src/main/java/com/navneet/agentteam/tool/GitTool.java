package com.navneet.agentteam.tool;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.Status;
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

    /** Opens the git repo for the given run's isolated workspace. */
    private Git openRepo(String runId) throws IOException {
        return Git.open(Path.of(workspaceRoot, runId).toFile());
    }

    // ── Worker-safe operations ────────────────────────────────────────────────

    public ToolResult createBranch(String runId, String branchName) {
        try (Git git = openRepo(runId)) {
            git.checkout().setCreateBranch(true).setName(branchName).call();
            return ToolResult.ok("Created and checked out branch: " + branchName);
        } catch (Exception e) {
            return ToolResult.err("Branch creation failed: " + e.getMessage());
        }
    }

    public ToolResult commit(String runId, String message) {
        try (Git git = openRepo(runId)) {
            git.add().addFilepattern(".").call();
            RevCommit commit = git.commit().setMessage(message).call();
            return ToolResult.ok("Committed: " + commit.abbreviate(7).name() + " — " + message);
        } catch (Exception e) {
            return ToolResult.err("Commit failed: " + e.getMessage());
        }
    }

    public ToolResult status(String runId) {
        try (Git git = openRepo(runId)) {
            Status s = git.status().call();
            StringBuilder sb = new StringBuilder();
            if (!s.getAdded().isEmpty())     sb.append("Added: ").append(s.getAdded()).append("\n");
            if (!s.getModified().isEmpty())  sb.append("Modified: ").append(s.getModified()).append("\n");
            if (!s.getUntracked().isEmpty()) sb.append("Untracked: ").append(s.getUntracked()).append("\n");
            return ToolResult.ok(sb.toString().isEmpty() ? "Clean working tree" : sb.toString());
        } catch (Exception e) {
            return ToolResult.err("Status failed: " + e.getMessage());
        }
    }

    public ToolResult diff(String runId) {
        try (Git git = openRepo(runId)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            git.diff().setOutputStream(out).call();
            String diff = out.toString();
            return ToolResult.ok(diff.isEmpty() ? "(no changes)" : diff);
        } catch (Exception e) {
            return ToolResult.err("Diff failed: " + e.getMessage());
        }
    }

    // ── Harness-only operations ───────────────────────────────────────────────

    public ToolResult mergeBranchToMain(String runId, String branchName) {
        try (Git git = openRepo(runId)) {
            git.checkout().setName("main").call();
            MergeResult result = git.merge()
                .include(git.getRepository().findRef(branchName))
                .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                .setCommit(true)
                .setMessage("chore: merge " + branchName)
                .call();
            if (result.getMergeStatus().isSuccessful()) {
                return ToolResult.ok("Merged " + branchName + " -> main");
            } else {
                return ToolResult.err("Merge conflict on " + branchName + ": " + result.getMergeStatus());
            }
        } catch (Exception e) {
            return ToolResult.err("Merge failed: " + e.getMessage());
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
     * Tags the run's workspace repo with "agent-run/{short-id}" after a successful merge.
     * Useful as a lightweight marker; non-fatal on failure.
     */
    public void tagRun(String runId) {
        String shortId  = runId.length() >= 8 ? runId.substring(0, 8) : runId;
        String tagName  = "agent-run/" + shortId;
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
     * Pushes the main branch in the run's workspace to a remote GitHub repository.
     * Non-fatal — returns ToolResult.err() on failure.
     */
    public ToolResult pushToRemote(String runId, String repoUrl, String pat) {
        try {
            String authUrl  = repoUrl.replace("https://", "https://oauth2:" + pat + "@");
            String wsPath   = Path.of(workspaceRoot, runId).toAbsolutePath().normalize().toString();
            ProcessBuilder pb = new ProcessBuilder(
                "git", "-C", wsPath, "push", authUrl, "main", "--force"
            );
            pb.redirectErrorStream(true);
            Process p      = pb.start();
            String  output = new String(p.getInputStream().readAllBytes());
            int     exit   = p.waitFor();
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
