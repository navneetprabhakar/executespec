package com.navneet.executespec.run.sync;

import com.navneet.executespec.harness.WorkspaceInitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Refreshes a finished run's workspace from a chosen branch on origin.
 * Used by the dashboard "Sync from branch" button after a PR has been
 * merged externally so the user can re-run review / tests / preview
 * against the post-merge code without restarting the whole pipeline.
 *
 * <p>Only safe on terminal runs — the API gates this; the service trusts
 * the caller. Uses {@code git fetch} + {@code git reset --hard} so
 * uncommitted local edits are intentionally wiped.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunSyncService {

    private final WorkspaceInitService workspaceInit;

    public record SyncOutcome(int reposSynced, List<String> details) {}

    public SyncOutcome sync(String runId, String branchOverride) {
        Path runRoot = workspaceInit.runWorkspacePath(runId);
        if (!Files.isDirectory(runRoot)) {
            throw new IllegalStateException("Run workspace not found: " + runRoot);
        }
        List<Path> repoRoots = enumerateRepoRoots(runRoot);
        List<String> details = new ArrayList<>();
        int synced = 0;
        for (Path repoRoot : repoRoots) {
            String name = repoRoot.equals(runRoot) ? "(root)" : repoRoot.getFileName().toString();
            String branch = (branchOverride != null && !branchOverride.isBlank())
                    ? branchOverride : detectDefaultBranch(repoRoot);
            try {
                runGit(repoRoot, "git", "fetch", "origin", branch);
                runGit(repoRoot, "git", "reset", "--hard", "origin/" + branch);
                synced++;
                details.add(name + " <- origin/" + branch);
                log.info("RunSync: run={} repo={} synced to origin/{}", runId, name, branch);
            } catch (Exception e) {
                details.add(name + " FAILED: " + e.getMessage());
                log.warn("RunSync: run={} repo={} failed: {}", runId, name, e.getMessage());
            }
        }
        return new SyncOutcome(synced, details);
    }

    /**
     * Multi-repo workspaces have {@code repos/<name>/} subdirectories; single-repo
     * runs use the workspace root. Returns the right set of git roots to refresh.
     */
    private List<Path> enumerateRepoRoots(Path runRoot) {
        Path multiBase = runRoot.resolve("repos");
        if (Files.isDirectory(multiBase)) {
            try (var stream = Files.list(multiBase)) {
                List<Path> out = new ArrayList<>();
                stream.filter(Files::isDirectory)
                        .filter(p -> Files.isDirectory(p.resolve(".git")))
                        .forEach(out::add);
                if (!out.isEmpty()) return out;
            } catch (Exception ignore) {}
        }
        if (Files.isDirectory(runRoot.resolve(".git"))) return List.of(runRoot);
        return List.of();
    }

    private String detectDefaultBranch(Path repoRoot) {
        try {
            // Resolve symbolic ref of origin/HEAD: outputs "refs/remotes/origin/<branch>"
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "symbolic-ref", "--short", "refs/remotes/origin/HEAD")
                    .directory(repoRoot.toFile())
                    .redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            if (out.startsWith("origin/")) return out.substring("origin/".length());
        } catch (Exception ignore) {}
        return "main";
    }

    private void runGit(Path dir, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(dir.toFile())
                .redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();
        if (exit != 0) {
            throw new RuntimeException("git " + String.join(" ", cmd) + " failed: " + out);
        }
    }
}
