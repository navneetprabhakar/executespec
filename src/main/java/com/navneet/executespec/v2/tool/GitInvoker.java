package com.navneet.executespec.v2.tool;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Internal helper that runs a {@code git} subprocess inside a workspace and returns
 * the combined stdout/stderr. Used by {@link GitStatusTool}, {@link GitDiffTool},
 * {@link GitCommitTool}.
 *
 * <p>Subprocess inherits {@code PATH} only — no other env vars leak through. Hard
 * 30-second timeout (git ops on a small workspace should never take longer).
 */
@Slf4j
final class GitInvoker {

    private GitInvoker() {}

    record GitResult(int exitCode, String output) {}

    static GitResult run(Path workspace, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        for (String a : args) cmd.add(a);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workspace.toFile());
        pb.environment().keySet().retainAll(List.of("PATH"));
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        boolean finished = proc.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            throw new IOException("git " + String.join(" ", args) + " exceeded 30s timeout");
        }
        String output = new String(proc.getInputStream().readAllBytes());
        return new GitResult(proc.exitValue(), output);
    }
}
