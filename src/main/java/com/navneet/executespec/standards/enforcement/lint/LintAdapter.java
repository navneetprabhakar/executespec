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
   * <p>An admitted linter executes repository-controlled plugin/check code, so when sandbox routing
   * is on it must be contained in the run's sandbox rather than run on the application host
   * (RR-1051 / RR-1674). A sandbox is scoped to a run, which is why {@code runId} is carried here:
   * it is what lets {@link LintProcessRunner} contain the command instead of reporting UNAVAILABLE.
   * A null or blank {@code runId} has no run scope and, with routing on, is UNAVAILABLE by design —
   * never a host fallback.
   *
   * @param runId the V2 run this lint belongs to; the scope that makes the command containable
   * @param repoRoot local path to the workspace root (cloned repo)
   * @param configPath optional path to the linter's config file (relative to repoRoot or absolute)
   * @param changedFiles files to focus on; empty = full project lint
   */
  LintResult run(String runId, Path repoRoot, Path configPath, List<Path> changedFiles);
}
