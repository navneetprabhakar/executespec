package com.navneet.executespec.testgen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import com.navneet.executespec.security.SafeWorkspacePath;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Owns the generated-test workspace boundary.
 *
 * <p>Generated tests used to live under the server process working directory at {@code
 * workspace-test/<runId>}. That directory was outside the run's admitted sandbox mount, forcing
 * compilation and execution onto the host or into an unrelated one-off container. The project now
 * lives at {@code agent.workspace/<runId>/.executespec/generated-tests}, inside the same
 * run-derived mount as generated application code.
 *
 * <p>The reserved path is resolved through {@link SafeWorkspacePath}; a repository-controlled
 * symlink at {@code .executespec} is a refusal, never a redirection outside the run workspace.
 */
@Component
public class TestWorkspaceResolver {

  public static final String RELATIVE_PROJECT_ROOT = ".executespec/generated-tests";
  public static final String CONTAINER_PROJECT_ROOT = "/workspace/" + RELATIVE_PROJECT_ROOT;

  private final Path agentWorkspaceRoot;

  @Autowired
  public TestWorkspaceResolver(@Value("${agent.workspace}") String agentWorkspaceRoot) {
    this(Path.of(agentWorkspaceRoot));
  }

  TestWorkspaceResolver(Path agentWorkspaceRoot) {
    this.agentWorkspaceRoot = agentWorkspaceRoot.toAbsolutePath().normalize();
  }

  /** Resolve the run-owned test project without creating or following repository symlinks. */
  public Path projectRoot(String runId) {
    if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
      throw new IllegalArgumentException("unsafe run id");
    }
    Path runRoot = agentWorkspaceRoot.resolve(runId).normalize();
    try {
      if (!Files.isDirectory(runRoot, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalStateException("run workspace is unavailable for generated tests");
      }
      SafeWorkspacePath.requireWithin(agentWorkspaceRoot, runRoot);
      return SafeWorkspacePath.resolveWithin(runRoot, RELATIVE_PROJECT_ROOT);
    } catch (IOException | SecurityException unsafe) {
      throw new IllegalStateException(
          "generated-test workspace is not safely contained in the run workspace", unsafe);
    }
  }

  public Path testSourceRoot(String runId) {
    return projectRoot(runId).resolve("src/test/java/com/executespec/tests");
  }
}
