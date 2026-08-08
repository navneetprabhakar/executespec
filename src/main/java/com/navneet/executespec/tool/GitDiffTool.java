package com.navneet.executespec.tool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * V2 gitDiff tool. Returns the working-tree diff (unstaged + staged) since HEAD, optionally limited
 * to a pathspec.
 *
 * <p>Harness-resolution T3 (2026-07-04): the old hard 3,000-char truncation made self-review of any
 * multi-file change physically impossible — a worker (or the critic/verifier) that wanted to
 * inspect its own patch before {@code completeTask} could not see it, while every other bulky tool
 * had the {@link ToolOutput#budget} spill-to-disk treatment. Now: 6KB inline + full diff persisted
 * to {@code .agent-context/tool-output/} with a readFile path, plus an optional {@code path} arg to
 * scope the diff.
 */
@Component
@Slf4j
public class GitDiffTool implements Tool {

  @Override
  public String name() {
    return "gitDiff";
  }

  @Override
  public String description() {
    return "Shows the diff of tracked changes (staged + unstaged) since the last commit, AND lists"
        + " untracked (new) files present in the workspace — those have no HEAD diff yet, so"
        + " readFile them to review their contents. Pass `path` to limit to one"
        + " file/directory. Large diffs are saved to a file — you get an inline preview plus"
        + " a path you can readFile for the complete diff. Use this to review your own"
        + " changes before completeTask.";
  }

  /** Bound on the untracked-file listing so one call can't flood the context. */
  private static final int MAX_UNTRACKED_LISTED = 500;

  @Override
  public String inputSchema() {
    return """
{
  "type": "object",
  "properties": {
    "path": {
      "type": "string",
      "description": "Optional workspace-relative file or directory to limit the diff to."
    }
  },
  "additionalProperties": false
}
""";
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    try {
      String path = args.hasNonNull("path") ? args.get("path").asText("").trim() : "";
      if (!path.isEmpty()) {
        // PathSafety keeps the pathspec inside the workspace (defence-in-depth; git would
        // also refuse, but with a confusing error).
        PathSafety.safeResolve(ctx.workspaceRoot(), path);
      }
      List<GitWorkspaceView.Repository> repositories =
          GitWorkspaceView.repositories(ctx.workspaceRoot());
      List<String> sections = new ArrayList<>();
      for (GitWorkspaceView.Repository repository : repositories) {
        String repositoryPath = GitWorkspaceView.repositoryRelativePath(repository, path);
        if (repositoryPath == null && repositories.size() == 1) repositoryPath = path;
        if (repositoryPath == null) continue;
        GitInvoker.GitResult r =
            repositoryPath.isEmpty()
                ? GitInvoker.run(repository.root(), "diff", "HEAD")
                : GitInvoker.run(repository.root(), "diff", "HEAD", "--", repositoryPath);
        if (r.exitCode() != 0) {
          return ToolResult.err(
              ToolResult.ErrorKind.IO_ERROR,
              "git diff failed in "
                  + repository.workspacePrefix()
                  + " (exit="
                  + r.exitCode()
                  + "): "
                  + r.output());
        }
        // `git diff HEAD` shows only tracked files. The worker's writeFile/editFile create files
        // but never `git add` (gitCommit is denied to the worker — see ToolRegistry.WORKER_DENY),
        // so brand-new files are UNTRACKED and invisible to the diff above. On a greenfield task
        // that is almost everything the worker produced, so a self-review here would falsely read
        // as
        // "(no changes)". List the untracked (non-ignored) files so the model knows they exist and
        // can readFile them, rather than concluding it produced nothing.
        String untracked = untrackedFilesNote(repository.root(), repositoryPath);
        StringBuilder body = new StringBuilder();
        if (!r.output().isBlank()) body.append(r.output().stripTrailing());
        if (!untracked.isEmpty()) {
          if (body.length() > 0) body.append('\n');
          body.append(untracked);
        }
        if (body.length() > 0) {
          sections.add(GitWorkspaceView.section(repository, body.toString()));
        }
      }
      if (sections.isEmpty()) return ToolResult.ok("(no changes)");
      String out = String.join("\n", sections);
      // T3: budget instead of amputate — inline preview + full spill with readFile path.
      return ToolResult.ok(ToolOutput.budget(ctx, "gitDiff", out, ToolOutput.DEFAULT_INLINE_CAP));
    } catch (IOException ioe) {
      log.warn("gitDiff error", ioe);
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return ToolResult.err(ToolResult.ErrorKind.INTERNAL, "interrupted");
    } catch (IllegalArgumentException iae) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, iae.getMessage());
    }
  }

  /**
   * Lists untracked (new) non-ignored files as a clearly-marked section, or "" when there are none.
   * {@code --exclude-standard} respects .gitignore / .git/info/exclude, so generated/vendor files
   * the platform marked ignored are correctly left out; only the worker's genuinely new source is
   * listed. A listing (rather than an inline new-file diff) keeps this cheap and multi-repo-safe —
   * the worker authored these files and can readFile any it wants to inspect.
   */
  private String untrackedFilesNote(java.nio.file.Path root, String repositoryPath)
      throws IOException, InterruptedException {
    GitInvoker.GitResult listing =
        (repositoryPath == null || repositoryPath.isEmpty())
            ? GitInvoker.run(root, "ls-files", "--others", "--exclude-standard")
            : GitInvoker.run(
                root, "ls-files", "--others", "--exclude-standard", "--", repositoryPath);
    // A non-zero ls-files exit means we could not enumerate untracked files. Do NOT silently claim
    // there are none — say enumeration failed so the model does not treat this as "no new files".
    if (listing.exitCode() != 0) {
      return "[note: could not list untracked files (git ls-files exit="
          + listing.exitCode()
          + ") — use gitStatus to see new files]";
    }
    if (listing.output().isBlank()) return "";
    String[] files = listing.output().split("\\r?\\n");
    StringBuilder sb = new StringBuilder();
    int shown = 0;
    int omitted = 0;
    for (String f : files) {
      String rel = f.trim();
      if (rel.isEmpty()) continue;
      if (shown >= MAX_UNTRACKED_LISTED) {
        omitted++;
        continue;
      }
      if (sb.length() == 0) {
        sb.append(
            "new (untracked) files — not shown as a HEAD diff; readFile to review their contents:");
      }
      sb.append("\n  ").append(rel);
      shown++;
    }
    if (omitted > 0) {
      sb.append("\n  ...(").append(omitted).append(" more untracked file(s) not listed)");
    }
    return sb.toString();
  }
}
