package com.navneet.executespec.security.dependency;

import java.util.List;
import java.util.regex.Pattern;

import com.navneet.executespec.security.guardrail.GuardrailLevel;
import com.navneet.executespec.security.guardrail.GuardrailLevelResolver;
import com.navneet.executespec.tool.ShellSandboxPolicy.ValidationResult;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 2026-05-02 — guardrail-tiered enforcement of dependency-install commands across the four JS
 * package managers we allow ({@code npm}, {@code bun}, {@code pnpm}, {@code yarn}). Sits behind
 * {@link com.navneet.executespec.tool.ShellSandboxPolicy} — when a chained command segment is
 * detected as an install invocation and the active {@link GuardrailLevel} is at least {@link
 * GuardrailLevel#MEDIUM}, this class enforces the missing security flags the worker forgot.
 *
 * <p><b>Threat model addressed.</b>
 *
 * <ul>
 *   <li><b>Lifecycle-script RCE</b> — every manager runs {@code postinstall} / {@code prepare}
 *       hooks on every dep at install time. {@code --ignore-scripts} (npm/bun/pnpm) / {@code
 *       --immutable} (yarn 2+) disables this. Mandatory at MEDIUM+. Planner can opt out per-task by
 *       declaring {@code requires_postinstall: true} in the plan; absent that declaration, scripts
 *       are blocked.
 *   <li><b>Lockfile drift</b> — {@code npm install} without a lockfile (or with mismatched
 *       lockfile) silently floats versions. {@code npm ci} / {@code bun install --frozen-lockfile}
 *       / {@code pnpm install --frozen-lockfile} reject install when the lockfile diverges.
 *       Mandatory at MEDIUM+.
 *   <li><b>Off-registry installs</b> — git+ssh, github tarball, file://, http://. Skips every
 *       registry-side scan. Blocked at MEDIUM+.
 *   <li><b>Pre-release / 0.0.0-beta versions</b> — supply-chain attackers commonly publish
 *       compromised versions as alpha/beta/rc/next/canary. Blocked at HIGH (configurable via {@code
 *       agent.security.deps.allow-prerelease}).
 *   <li><b>Known-CVE deps</b> — managers' built-in {@code audit} subcommand checks installed deps
 *       against the public CVE DB. {@code --audit-level=high} on npm + {@code && <manager> audit}
 *       chained for bun/pnpm/yarn is mandatory at HIGH.
 *   <li><b>Registry hijack</b> — adversary points {@code .npmrc} at an attacker-controlled
 *       registry. {@code --registry=<allowed>} pin enforced at HIGH against {@code
 *       agent.security.deps.allowed-registries}.
 * </ul>
 *
 * <p><b>Outside this policy's scope.</b> True network egress blocking (a malicious dep doing
 * outbound HTTP <em>after</em> install) needs container-level isolation — the worker today runs in
 * the same JVM context as the orchestrator. {@link
 * com.navneet.executespec.security.dependency.SocketDevAdapter} stub is the placeholder for a
 * deeper supply-chain scanner integration when an org's ready to wire one in.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DependencySecurityPolicy {

  private final GuardrailLevelResolver guardrailLevelResolver;

  /** When true, MEDIUM+ install commands without {@code --ignore-scripts} are blocked. */
  @Value("${agent.security.deps.require-ignore-scripts:true}")
  private boolean requireIgnoreScripts;

  /** When true, MEDIUM+ install commands without lockfile-mode flags are blocked. */
  @Value("${agent.security.deps.require-lockfile:true}")
  private boolean requireLockfile;

  /** When true, HIGH install commands without an audit pass are blocked. */
  @Value("${agent.security.deps.require-audit:true}")
  private boolean requireAudit;

  /** When false, HIGH install commands citing pre-release versions are blocked. */
  @Value("${agent.security.deps.allow-prerelease:false}")
  private boolean allowPrerelease;

  /**
   * Comma-separated list of registry origins allowed at HIGH. Empty = no registry pin enforcement
   * (worker may use any registry). Defaults to the official npm + bun + pnpm registries. Override
   * per-org via env / yaml.
   */
  @Value(
      "${agent.security.deps.allowed-registries:https://registry.npmjs.org,"
          + "https://registry.bun.sh,https://registry.pnpm.io}")
  private String allowedRegistriesCsv;

  /** Pre-release version like {@code 1.2.3-beta.4}, {@code 0.0.0-canary}, {@code 2.0.0-rc.1}. */
  private static final Pattern PRERELEASE =
      Pattern.compile(
          "\\b\\d+\\.\\d+\\.\\d+-" + "(?:alpha|beta|rc|next|canary|nightly|insiders|dev|pre)\\b",
          Pattern.CASE_INSENSITIVE);

  /** Off-registry source — git+ssh, github tarball, http url, file path. */
  private static final Pattern OFF_REGISTRY =
      Pattern.compile(
          "(?:git\\+ssh://|git\\+https://|git://|file://|"
              + "https?://(?!registry\\.npmjs\\.org|registry\\.bun\\.sh|"
              + "registry\\.pnpm\\.io|registry\\.yarnpkg\\.com))",
          Pattern.CASE_INSENSITIVE);

  /** Detects {@code --registry=<url>} or {@code --registry <url>}. */
  private static final Pattern REGISTRY_FLAG =
      Pattern.compile("--registry[\\s=]([^\\s]+)", Pattern.CASE_INSENSITIVE);

  /**
   * Validate a single chained-command segment that has already passed allowlist + pattern checks.
   * Returns {@link ValidationResult#allow()} when the segment isn't an install command or when the
   * active guardrail level is LOW; otherwise enforces the rules above.
   *
   * <p>The full chained command is also passed in so the audit-step check can verify {@code bun
   * install ... && bun audit ...} is present (audit lives on a separate segment for bun/pnpm/yarn
   * since they don't have an inline {@code --audit} flag).
   */
  public ValidationResult validateSegment(String segment, String fullCommand) {
    GuardrailLevel level =
        guardrailLevelResolver != null ? guardrailLevelResolver.current() : GuardrailLevel.LOW;
    return validateSegment(segment, fullCommand, level);
  }

  public ValidationResult validateSegment(
      String segment, String fullCommand, GuardrailLevel level) {
    GuardrailLevel effective = level == null ? GuardrailLevel.LOW : level;
    if (effective == GuardrailLevel.LOW) {
      return ValidationResult.allow(); // legacy behaviour, no enforcement
    }

    InstallInvocation inv = parseInstallInvocation(segment);
    if (inv == null) {
      return ValidationResult.allow(); // not an install command
    }

    if (requireIgnoreScripts && !hasIgnoreScripts(segment, inv.tool)) {
      return block(
          effective,
          "Dependency install requires "
              + ignoreScriptsHint(inv.tool)
              + " under guardrail "
              + effective
              + " (lifecycle-script RCE prevention). Got: "
              + segment);
    }

    if (requireLockfile && !isLockfileMode(segment, inv)) {
      return block(
          effective,
          "Dependency install requires lockfile mode under guardrail "
              + effective
              + " — use "
              + lockfileHint(inv.tool)
              + ". Got: "
              + segment);
    }

    if (containsOffRegistrySource(segment)) {
      return block(
          effective,
          "Off-registry dependency source (git+, github tarball, http URL, file://) blocked at"
              + " guardrail "
              + effective
              + ". Install from a known package registry instead. Got: "
              + segment);
    }

    if (effective == GuardrailLevel.HIGH) {
      if (requireAudit && !hasAuditCoverage(fullCommand, inv.tool)) {
        return block(
            effective,
            "HIGH guardrail requires the install to gate on a vulnerability audit — append "
                + auditHint(inv.tool)
                + " to the chained command, OR add "
                + inlineAuditHint(inv.tool)
                + " when the manager supports an inline flag. Got: "
                + segment);
      }
      if (!allowPrerelease && PRERELEASE.matcher(segment).find()) {
        return block(
            effective,
            "Pre-release dependency version (alpha/beta/rc/canary/nightly) blocked at HIGH —"
                + " supply-chain attackers commonly publish through these channels. Pin to a"
                + " stable release. Got: "
                + segment);
      }
      ValidationResult registryResult = validateRegistry(segment);
      if (!registryResult.permitted()) {
        return registryResult;
      }
    }

    return ValidationResult.allow();
  }

  /** Recognised install-command shape. {@code tool} is the package manager name. */
  record InstallInvocation(String tool, String verb) {}

  static InstallInvocation parseInstallInvocation(String segment) {
    String[] tokens = segment.trim().split("\\s+");
    if (tokens.length < 2) return null;
    String tool = tokens[0];
    if (tool.contains("/")) tool = tool.substring(tool.lastIndexOf('/') + 1);
    String verb = tokens[1];

    return switch (tool) {
      case "npm" -> {
        if (verb.equals("install") || verb.equals("i") || verb.equals("ci"))
          yield new InstallInvocation("npm", verb);
        yield null;
      }
      case "bun" -> {
        if (verb.equals("install") || verb.equals("i") || verb.equals("add"))
          yield new InstallInvocation("bun", verb);
        yield null;
      }
      case "pnpm" -> {
        if (verb.equals("install") || verb.equals("i") || verb.equals("add"))
          yield new InstallInvocation("pnpm", verb);
        yield null;
      }
      case "yarn" -> {
        // yarn with no verb defaults to install
        if (tokens.length == 1
            || verb.equals("install")
            || verb.equals("add")
            || verb.startsWith("--")) {
          yield new InstallInvocation("yarn", verb);
        }
        yield null;
      }
      default -> null;
    };
  }

  private static boolean hasIgnoreScripts(String segment, String tool) {
    String s = " " + segment + " ";
    return switch (tool) {
      case "npm", "bun", "pnpm" -> s.contains(" --ignore-scripts");
        // yarn 1: --ignore-scripts; yarn 2+: env NPM_CONFIG_IGNORE_SCRIPTS=1 OR --immutable
      case "yarn" -> s.contains(" --ignore-scripts") || s.contains(" --immutable");
      default -> true; // unknown — let allowlist handle it
    };
  }

  private static boolean isLockfileMode(String segment, InstallInvocation inv) {
    String s = " " + segment + " ";
    return switch (inv.tool) {
      case "npm" -> inv.verb.equals("ci") || s.contains(" --frozen-lockfile");
      case "bun" -> s.contains(" --frozen-lockfile");
      case "pnpm" -> s.contains(" --frozen-lockfile");
      case "yarn" -> s.contains(" --immutable") || s.contains(" --frozen-lockfile");
      default -> true;
    };
  }

  private static boolean containsOffRegistrySource(String segment) {
    return OFF_REGISTRY.matcher(segment).find();
  }

  private static boolean hasAuditCoverage(String fullCommand, String tool) {
    String c = fullCommand == null ? "" : fullCommand.toLowerCase();
    return switch (tool) {
      case "npm" -> c.contains("--audit-level") || c.contains("npm audit");
      case "bun" -> c.contains("bun audit");
      case "pnpm" -> c.contains("pnpm audit");
      case "yarn" -> c.contains("yarn audit") || c.contains("yarn npm audit");
      default -> true;
    };
  }

  private ValidationResult validateRegistry(String segment) {
    var m = REGISTRY_FLAG.matcher(segment);
    List<String> allowed = parseAllowedRegistries();
    if (!m.find()) {
      // No --registry flag → defaults to the registry baked into config; that's fine when the
      // allowlist contains the manager's default. Skip enforcement when no allowlist configured.
      return ValidationResult.allow();
    }
    String supplied = m.group(1).trim();
    for (String a : allowed) {
      if (supplied.startsWith(a)) return ValidationResult.allow();
    }
    return block(
        GuardrailLevel.HIGH,
        "Registry "
            + supplied
            + " not in agent.security.deps.allowed-registries allowlist ("
            + allowed
            + "). Use one of the configured registries.");
  }

  private List<String> parseAllowedRegistries() {
    if (allowedRegistriesCsv == null || allowedRegistriesCsv.isBlank()) return List.of();
    return List.of(allowedRegistriesCsv.split("\\s*,\\s*"));
  }

  private static String ignoreScriptsHint(String tool) {
    return switch (tool) {
      case "npm", "bun", "pnpm" -> "the `--ignore-scripts` flag";
      case "yarn" -> "the `--ignore-scripts` (yarn 1) or `--immutable` (yarn 2+) flag";
      default -> "the `--ignore-scripts` flag";
    };
  }

  private static String lockfileHint(String tool) {
    return switch (tool) {
      case "npm" -> "`npm ci` (preferred) or `npm install --frozen-lockfile`";
      case "bun" -> "`bun install --frozen-lockfile`";
      case "pnpm" -> "`pnpm install --frozen-lockfile`";
      case "yarn" -> "`yarn install --immutable` (yarn 2+) or `yarn install --frozen-lockfile`";
      default -> "the manager's lockfile-strict flag";
    };
  }

  private static String auditHint(String tool) {
    return switch (tool) {
      case "npm" -> "`&& npm audit --audit-level=high`";
      case "bun" -> "`&& bun audit`";
      case "pnpm" -> "`&& pnpm audit --audit-level=high`";
      case "yarn" -> "`&& yarn npm audit --severity high`";
      default -> "an audit step";
    };
  }

  private static String inlineAuditHint(String tool) {
    return switch (tool) {
      case "npm" -> "`--audit-level=high`";
      default -> "an inline audit flag (not supported for this tool — use the chained form)";
    };
  }

  private ValidationResult block(GuardrailLevel level, String message) {
    log.warn("DEP-SECURITY BLOCKED guardrail={} — {}", level, message);
    if (guardrailLevelResolver != null) guardrailLevelResolver.recordBlock("deps");
    return ValidationResult.block(message);
  }
}
