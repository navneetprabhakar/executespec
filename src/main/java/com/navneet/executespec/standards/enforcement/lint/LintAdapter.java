package com.navneet.executespec.standards.enforcement.lint;

import java.nio.file.Path;
import java.util.List;

/**
 * E3.4 — strategy interface for lint enforcement. One implementation per linter the platform
 * supports. v1 ships ESLint + Checkstyle (cut from the source plan's 5 per the eval doc §7); Detekt
 * / ruff / golangci land on demand.
 *
 * <p>Implementations must be stateless + thread-safe (one bean shared across all enforcement runs).
 * They must NEVER throw — return a {@link LintResult} with {@code status=UNAVAILABLE} when the CLI
 * isn't installed (gracefully degrades; future deploy installs CLI + adapter activates without code
 * change).
 */
public interface LintAdapter {

  /** Linter identifier (e.g. {@code "eslint"}). */
  String tool();

  /**
   * Run the linter against the given workspace + config.
   *
   * @param repoRoot local path to the workspace root (cloned repo)
   * @param configPath optional path to the linter's config file (relative to repoRoot or absolute)
   * @param changedFiles files to focus on; empty = full project lint
   */
  LintResult run(Path repoRoot, Path configPath, List<Path> changedFiles);
}
