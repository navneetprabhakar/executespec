package com.navneet.executespec.standards.enforcement.lint;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * RR-1051 — configuration for repository-scoped lint execution.
 *
 * <p>Running a linter over a customer repository is <em>executing customer-authored code</em>, not
 * reading it. ESLint's flat config ({@code eslint.config.js}) is JavaScript by design, plugins and
 * shareable configs are npm packages the repository chooses, and Checkstyle configs can name custom
 * check classes. There is no version of "just lint it" that is merely a read.
 *
 * <p>Therefore host execution is <strong>off by default</strong>. With it off, {@link LintAdapter}
 * implementations report {@link LintResult.Status#UNAVAILABLE}, which the enforcement pipeline
 * already surfaces as a critical "could not be verified" finding and a PARTIAL run. That is an
 * honest unverified result rather than a silent pass, and it is the only safe default while the
 * platform has no containerised execution seam for this path.
 */
@Data
@ConfigurationProperties(prefix = "executespec.standards.lint")
public class LintExecutionProperties {

  /**
   * Explicit operator acknowledgement that this host may execute repository-controlled code.
   *
   * <p>Turning this on does not make lint execution safe; it asserts that the host is a disposable,
   * credential-free execution environment. Leave it off on any host that also serves customer
   * traffic or holds provider credentials.
   */
  private boolean hostExecutionEnabled = false;

  /**
   * Pinned absolute paths to operator-installed linter executables, keyed by {@link
   * LintAdapter#tool()}.
   *
   * <p>A tool with no entry here cannot run. Resolution never consults {@code PATH} and never uses
   * {@code npx}, which resolves and executes packages chosen by the repository (or downloaded from
   * the network) rather than by the operator.
   */
  private Map<String, String> binaries = new LinkedHashMap<>();

  /** Wall-clock ceiling for one linter invocation. */
  private long timeoutSeconds = 60;

  /** Cap on captured stdout and stderr, each. Prevents an unbounded child from exhausting heap. */
  private long maxOutputBytes = 4L * 1024 * 1024;

  /**
   * The only environment variables passed to the child. Everything else — provider API keys, SCM
   * tokens, database credentials — is stripped. {@code HOME} is always replaced with a scratch
   * directory rather than inherited, so credential files under the service account's home are not
   * offered to the child.
   */
  private Set<String> environmentAllowlist =
      new LinkedHashSet<>(java.util.List.of("PATH", "LANG", "LC_ALL", "TZ"));
}
