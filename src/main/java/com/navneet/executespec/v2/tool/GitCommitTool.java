package com.navneet.executespec.v2.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.navneet.executespec.harness.VendoredPathGuard;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * V2 gitCommit tool. Stages all changes and commits with the supplied message.
 *
 * <p>Sequential V2 has no per-task branch — all commits land on whatever branch is currently
 * checked out (the run's main workspace branch).
 */
@Component
@Slf4j
public class GitCommitTool implements Tool {

  private final ObjectProvider<VendoredPathGuard> vendoredPathGuardProvider;

  /** Spring-driven constructor — uses ObjectProvider so unit tests can construct without wiring. */
  @Autowired
  public GitCommitTool(ObjectProvider<VendoredPathGuard> vendoredPathGuardProvider) {
    this.vendoredPathGuardProvider = vendoredPathGuardProvider;
  }

  /** Test-only no-arg constructor — no pre-commit guard fires. */
  public GitCommitTool() {
    this.vendoredPathGuardProvider = null;
  }

  @Override
  public String name() {
    return "gitCommit";
  }

  @Override
  public String description() {
    return "Stages all working-tree changes (git add -A) and creates a commit with the given"
        + " message.";
  }

  @Override
  public String inputSchema() {
    return """
           {
             "type": "object",
             "properties": {
               "message": {
                 "type": "string",
                 "description": "Commit message",
                 "minLength": 1
               }
             },
             "required": ["message"],
             "additionalProperties": false
           }
           """;
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    String message = args.path("message").asText(null);
    if (message == null || message.isBlank()) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, "message is required");
    }
    try {
      GitInvoker.GitResult addRes = GitInvoker.run(ctx.workspaceRoot(), "add", "-A");
      if (addRes.exitCode() != 0) {
        return ToolResult.err(
            ToolResult.ErrorKind.IO_ERROR,
            "git add failed (exit=" + addRes.exitCode() + "): " + addRes.output());
      }
      // 2026-05-02 — defense-in-depth pre-commit guard against vendored paths. The post-clone
      // .gitignore augmentation in WorkspaceInitService is the primary line of defense; this
      // catches the rare case where the worker writes a sibling repo or runs a `git init` in a
      // subdir that bypassed the post-clone hook. Run on each git checkout under the
      // workspace root (workspace + every repos/<name>/ subdir).
      int purged = guardEveryGitCheckoutBelow(ctx.workspaceRoot());
      if (purged > 0) {
        log.warn(
            "GitCommitTool: pre-commit guard purged {} vendored path(s) before commit (run={}, task={})",
            purged,
            ctx.runId(),
            ctx.taskId());
        // Re-run `git add -A` so the clean index reflects the augmented .gitignore.
        GitInvoker.run(ctx.workspaceRoot(), "add", "-A");
      }
      GitInvoker.GitResult commitRes =
          GitInvoker.run(ctx.workspaceRoot(), "commit", "-m", message, "--allow-empty");
      if (commitRes.exitCode() != 0) {
        return ToolResult.err(
            ToolResult.ErrorKind.IO_ERROR,
            "git commit failed (exit=" + commitRes.exitCode() + "): " + commitRes.output());
      }
      return ToolResult.ok(commitRes.output());
    } catch (IOException ioe) {
      log.warn("gitCommit error", ioe);
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return ToolResult.err(ToolResult.ErrorKind.INTERNAL, "interrupted");
    }
  }

  /**
   * Walk the workspace root + every {@code repos/<name>/} subdir and run the vendored-path purge
   * on each git checkout we find. Returns the total number of purged paths across all repos.
   * Skips when the {@link VendoredPathGuard} bean is unavailable (test seam).
   */
  private int guardEveryGitCheckoutBelow(Path workspaceRoot) {
    if (vendoredPathGuardProvider == null) return 0;
    VendoredPathGuard guard = vendoredPathGuardProvider.getIfAvailable();
    if (guard == null) return 0;
    int total = 0;
    if (Files.isDirectory(workspaceRoot.resolve(".git"))) {
      total += guard.purgeStagedVendored(workspaceRoot);
    }
    Path reposDir = workspaceRoot.resolve("repos");
    if (Files.isDirectory(reposDir)) {
      try (var stream = Files.list(reposDir)) {
        for (var p : (Iterable<Path>) stream::iterator) {
          if (Files.isDirectory(p) && Files.isDirectory(p.resolve(".git"))) {
            total += guard.purgeStagedVendored(p);
          }
        }
      } catch (IOException ignored) {
        // best-effort
      }
    }
    return total;
  }
}
