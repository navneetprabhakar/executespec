package com.navneet.executespec.standards.enforcement.lint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.security.SafeWorkspacePath;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * RR-1051 — the admission boundary for executing a linter against a customer repository.
 *
 * <p>Every {@link LintAdapter} must obtain an {@link Admission} before it builds an argument
 * vector. A denial is not an error: it is returned to the caller as {@link
 * LintResult.Status#UNAVAILABLE} with a reason, which the enforcement pipeline already renders as
 * an explicit "could not be verified" finding.
 *
 * <p>What this class can enforce, and what it deliberately does not claim:
 *
 * <ul>
 *   <li><b>Enforced.</b> Off by default. No {@code PATH} lookup and no {@code npx}: the executable
 *       must be an operator-pinned absolute path. Config files must resolve inside the repository
 *       root, may not be symlinks, and may not traverse out. Target files are contained the same
 *       way.
 *   <li><b>Not enforced here.</b> Process, filesystem and network isolation. Once an admitted
 *       linter starts, it is arbitrary code running as the service account with host network
 *       access. Containment requires the containerised execution seam that does not yet exist for
 *       this path; until it does, {@link LintExecutionProperties#isHostExecutionEnabled()} is the
 *       only honest control and its default is off.
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class LintExecutionPolicy {

  private final LintExecutionProperties properties;

  /**
   * Outcome of admission.
   *
   * @param executable operator-pinned absolute executable path; null when denied
   * @param configArgument repository-contained config path to pass to the linter, or null
   * @param targetArguments repository-contained file arguments, empty for a whole-project lint
   * @param denialReason populated only when {@link #allowed()} is false
   */
  public record Admission(
      String executable, String configArgument, List<String> targetArguments, String denialReason) {

    public boolean allowed() {
      return denialReason == null;
    }

    static Admission denied(String reason) {
      return new Admission(null, null, List.of(), reason);
    }
  }

  public long timeoutSeconds() {
    return properties.getTimeoutSeconds();
  }

  /**
   * Decide whether {@code tool} may run against {@code repoRoot}.
   *
   * @param tool {@link LintAdapter#tool()}
   * @param repoRoot workspace root; every path argument must resolve inside it
   * @param configPath optional linter config, repo-relative or absolute
   * @param targetFiles optional focused file list; empty means whole project
   */
  public Admission admit(String tool, Path repoRoot, Path configPath, List<Path> targetFiles) {
    if (!properties.isHostExecutionEnabled()) {
      return Admission.denied(
          "repository-controlled lint execution is disabled on this host "
              + "(executespec.standards.lint.host-execution-enabled=false); a linter config is "
              + "customer-authored executable code and is not run on the application host");
    }
    if (!isExistingDirectory(repoRoot)) {
      return Admission.denied("workspace root is not an existing directory");
    }

    String configured = properties.getBinaries().get(tool);
    if (configured == null || configured.isBlank()) {
      return Admission.denied(
          "no operator-pinned executable configured for '"
              + tool
              + "' (set executespec.standards.lint.binaries."
              + tool
              + "); PATH and npx resolution are refused because they let the repository or the "
              + "network choose the binary");
    }
    Path executable = Path.of(configured);
    if (!executable.isAbsolute()) {
      return Admission.denied("pinned executable for '" + tool + "' must be an absolute path");
    }
    if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
        || !Files.isExecutable(executable)) {
      return Admission.denied(
          "pinned executable for '" + tool + "' is not an executable regular file");
    }

    String configArgument = null;
    if (configPath != null) {
      Path contained = containedPath(repoRoot, configPath);
      if (contained == null) {
        return Admission.denied(
            "lint config must resolve inside the workspace root and may not be a symlink");
      }
      if (!Files.isRegularFile(contained, LinkOption.NOFOLLOW_LINKS)) {
        return Admission.denied("configured lint config file does not exist in the workspace");
      }
      configArgument = contained.toString();
    }

    List<String> targets = new ArrayList<>();
    if (targetFiles != null) {
      for (Path file : targetFiles) {
        Path contained = containedPath(repoRoot, file);
        if (contained == null) {
          return Admission.denied(
              "lint target must resolve inside the workspace root and may not be a symlink");
        }
        targets.add(contained.toString());
      }
    }
    return new Admission(executable.toString(), configArgument, List.copyOf(targets), null);
  }

  private static boolean isExistingDirectory(Path candidate) {
    if (candidate == null) return false;
    try {
      return Files.isDirectory(candidate.toRealPath());
    } catch (IOException unreadable) {
      return false;
    }
  }

  /**
   * Resolve {@code candidate} against {@code root} and prove the result is inside it.
   *
   * <p>Delegates to the shared {@link SafeWorkspacePath} boundary rather than repeating the rules:
   * a repository can plant a symlink at any component of the chain, so containment has to be judged
   * on canonical paths with no link in any existing component. Returns null on refusal because the
   * caller turns that into an UNAVAILABLE lint result rather than an exception.
   */
  private static Path containedPath(Path root, Path candidate) {
    try {
      Path resolved = (candidate.isAbsolute() ? candidate : root.resolve(candidate)).normalize();
      SafeWorkspacePath.requireWithin(root, resolved);
      return resolved;
    } catch (IOException | RuntimeException refused) {
      return null;
    }
  }
}
