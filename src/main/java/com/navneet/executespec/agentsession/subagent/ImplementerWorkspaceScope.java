package com.navneet.executespec.agentsession.subagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.security.SafeWorkspacePath;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Server-authoritative scope binder for the writer/IMPLEMENTER seam.
 *
 * <p>The seam is reached by CLI/VS Code ("local hands") clients, but every tool it dispatches —
 * {@code writeFile}, {@code editFile}, {@code readFile}, {@code buildProject} — executes on the
 * ExecuteSpec server, against the server's filesystem. Before this class existed the caller
 * supplied the target directory as an absolute path and the only validation was {@code
 * Files.isDirectory}, so any authenticated PAT holder could name an existing writable server
 * directory and have the platform overwrite it (and, through the model loop, read it and run its
 * build/test commands).
 *
 * <p>The root is therefore derived from server state, never from the request. A run-scoped session
 * binds to that run's workspace ({@code <agent.workspace>/<runId>}); every other session binds to
 * its own sandbox ({@code <agent.workspace>/agent-sessions/<sessionId>}). The request may only
 * narrow that root with a <em>relative</em> subpath, which is proven contained by {@link
 * SafeWorkspacePath} (no {@code ..}, no symlink component, no escape). An absolute path is refused
 * with an explicit message rather than silently reinterpreted, because a client that sent one
 * believed it was addressing its own machine.
 *
 * <p>This bounds the blast radius to server-owned agent workspaces. It does <em>not</em> turn the
 * seam into client-side execution: mutations still land on the server, and a genuine local-hands
 * writer needs the signed tool-call/result envelope protocol the run pipeline uses.
 */
@Component
public class ImplementerWorkspaceScope {

  /** Session and run ids become path segments, so they must be inert. */
  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_.\\-]{1,80}");

  private static final String SESSION_SANDBOX_DIR = "agent-sessions";

  private final Path workspaceRoot;

  public ImplementerWorkspaceScope(@Value("${agent.workspace:workspace}") String workspaceRoot) {
    this.workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize();
  }

  /**
   * Resolve the authorized server directory for this session, optionally narrowed by a relative
   * subpath supplied by the caller.
   *
   * @throws IllegalArgumentException when the requested scope is absolute or otherwise unusable
   * @throws SecurityException when the requested scope escapes the session's sandbox
   */
  public Path resolve(AgentSession session, String requestedScope) {
    if (session == null) {
      throw new IllegalArgumentException("agent session is required to resolve a writer scope");
    }
    Path sandbox = sandboxRoot(session);
    try {
      Files.createDirectories(sandbox);
      Path root = sandbox.toRealPath();
      if (requestedScope == null || requestedScope.isBlank()) {
        return root;
      }
      Path requested = Path.of(requestedScope);
      if (requested.isAbsolute()) {
        throw new IllegalArgumentException(
            "writer scope must be a path relative to this session's server workspace; absolute"
                + " paths are not accepted because the writer executes on the ExecuteSpec server,"
                + " not on your machine");
      }
      Path resolved = SafeWorkspacePath.resolveWithin(root, requestedScope);
      Files.createDirectories(resolved);
      return resolved.toRealPath();
    } catch (java.nio.file.InvalidPathException e) {
      throw new IllegalArgumentException("writer scope is not a valid path");
    } catch (IOException e) {
      throw new IllegalStateException(
          "writer scope is unavailable: " + e.getClass().getSimpleName());
    }
  }

  private Path sandboxRoot(AgentSession session) {
    String runId = session.getRunId();
    if (runId != null && !runId.isBlank()) {
      return workspaceRoot.resolve(safeSegment(runId, "run id"));
    }
    return workspaceRoot
        .resolve(SESSION_SANDBOX_DIR)
        .resolve(safeSegment(session.getId(), "session id"));
  }

  private static String safeSegment(String value, String label) {
    if (value == null || !SAFE_ID.matcher(value).matches()) {
      throw new IllegalArgumentException(label + " is not a usable workspace path segment");
    }
    return value;
  }
}
