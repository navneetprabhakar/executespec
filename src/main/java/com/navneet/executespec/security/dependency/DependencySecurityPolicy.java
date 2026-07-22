package com.navneet.executespec.security.dependency;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 *       hooks on every dep at install time. Only a flag or environment variable that actually
 *       disables script execution counts: {@code --ignore-scripts} (npm/bun/pnpm/yarn classic),
 *       {@code --mode=skip-build} or {@code YARN_ENABLE_SCRIPTS=false} (yarn berry), or a truthy
 *       {@code NPM_CONFIG_IGNORE_SCRIPTS} for the npm-config-reading managers. Mandatory at
 *       MEDIUM+.
 *       <p><b>RR-247.</b> Yarn's {@code --immutable} was previously accepted here as equivalent to
 *       {@code --ignore-scripts}. It is not: {@code --immutable} fails the install when the
 *       lockfile would change and has no effect whatsoever on lifecycle/build scripts, so a run
 *       under MEDIUM/HIGH was reported as "lifecycle-script RCE prevention" active while every
 *       {@code postinstall} in the dependency tree still executed. {@code --immutable} remains a
 *       valid <em>lockfile</em>-mode flag below and nothing more.
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
 *       <p><b>RR-248.</b> The allowlist check used {@code supplied.startsWith(allowedOrigin)}, and
 *       the off-registry regex used an unanchored negative lookahead. A prefix test on a URL is not
 *       an origin test: {@code https://registry.npmjs.org.evil.example} and {@code
 *       https://registry.npmjs.org@evil.example} both begin with an allowed origin while resolving
 *       to an attacker host. Both checks now parse the URI and compare scheme, host and effective
 *       port exactly (path prefix on a segment boundary), and reject userinfo.
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

  /**
   * Off-registry source specifier. Never permitted at MEDIUM+.
   *
   * <p>Widened beyond the RR-248 report: the previous pattern required {@code file://}, so npm's
   * ordinary single-colon {@code file:../pkg} form — and the {@code github:} / {@code gitlab:} /
   * {@code bitbucket:} / {@code gist:} shorthands, which npm resolves to a Git clone — were never
   * detected as off-registry at all.
   */
  private static final Pattern OFF_REGISTRY_SCHEME =
      Pattern.compile(
          "(?:^|[\\s=\"'])(?:git\\+ssh:|git\\+https:|git\\+file:|git:|file:|github:|gitlab:"
              + "|bitbucket:|gist:)",
          Pattern.CASE_INSENSITIVE);

  /** Any http(s) URL in the segment. Each one's HOST is checked, never a string prefix. */
  private static final Pattern HTTP_URL =
      Pattern.compile("https?://[^\\s\"'`;|&)]+", Pattern.CASE_INSENSITIVE);

  /**
   * Registry hosts a dependency URL may legitimately point at. Exact host match — a suffix such as
   * {@code registry.npmjs.org.evil.example} is NOT a member (RR-248).
   */
  private static final Set<String> KNOWN_REGISTRY_HOSTS =
      Set.of("registry.npmjs.org", "registry.bun.sh", "registry.pnpm.io", "registry.yarnpkg.com");

  /** Leading {@code NAME=value} environment assignments before the package-manager token. */
  private static final Pattern ENV_ASSIGNMENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=.*$");

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

    if (requireIgnoreScripts && !hasIgnoreScripts(inv)) {
      return block(
          effective,
          "MISSING_IGNORE_SCRIPTS",
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
          "MISSING_LOCKFILE_MODE",
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
          "OFF_REGISTRY_SOURCE",
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
            "MISSING_AUDIT",
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
            "PRERELEASE_VERSION",
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

  /**
   * Recognised install-command shape. {@code tool} is the package manager name, {@code env} holds
   * the leading {@code NAME=value} assignments that precede it (an env prefix used to hide the
   * whole command from this policy, because {@code tokens[0]} was the assignment rather than the
   * manager).
   */
  record InstallInvocation(String tool, String verb, String args, Map<String, String> env) {}

  static InstallInvocation parseInstallInvocation(String segment) {
    if (segment == null) return null;
    String[] tokens = segment.trim().split("\\s+");
    Map<String, String> env = new LinkedHashMap<>();
    int i = 0;
    while (i < tokens.length && ENV_ASSIGNMENT.matcher(tokens[i]).matches()) {
      int eq = tokens[i].indexOf('=');
      env.put(
          tokens[i].substring(0, eq).toUpperCase(Locale.ROOT),
          stripQuotes(tokens[i].substring(eq + 1)));
      i++;
    }
    if (i >= tokens.length) return null;
    String tool = tokens[i];
    if (tool.contains("/")) tool = tool.substring(tool.lastIndexOf('/') + 1);
    String verb = i + 1 < tokens.length ? tokens[i + 1] : "";
    // Everything from the manager token onward — the flag surface this policy inspects.
    String args = String.join(" ", java.util.Arrays.copyOfRange(tokens, i, tokens.length));

    return switch (tool) {
      case "npm" -> {
        if (verb.equals("install") || verb.equals("i") || verb.equals("ci"))
          yield new InstallInvocation("npm", verb, args, env);
        yield null;
      }
      case "bun" -> {
        if (verb.equals("install") || verb.equals("i") || verb.equals("add"))
          yield new InstallInvocation("bun", verb, args, env);
        yield null;
      }
      case "pnpm" -> {
        if (verb.equals("install") || verb.equals("i") || verb.equals("add"))
          yield new InstallInvocation("pnpm", verb, args, env);
        yield null;
      }
      case "yarn" -> {
        // yarn with no verb defaults to install
        if (verb.isEmpty()
            || verb.equals("install")
            || verb.equals("add")
            || verb.startsWith("--")) {
          yield new InstallInvocation("yarn", verb, args, env);
        }
        yield null;
      }
      default -> null;
    };
  }

  private static String stripQuotes(String v) {
    if (v.length() >= 2
        && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
      return v.substring(1, v.length() - 1);
    }
    return v;
  }

  private static boolean isTruthy(String v) {
    if (v == null) return false;
    String t = v.trim().toLowerCase(Locale.ROOT);
    return t.equals("1") || t.equals("true") || t.equals("yes") || t.equals("on");
  }

  private static boolean isFalsy(String v) {
    if (v == null) return false;
    String t = v.trim().toLowerCase(Locale.ROOT);
    return t.equals("0") || t.equals("false") || t.equals("no") || t.equals("off");
  }

  /**
   * RR-247 — true only when the invocation actually disables dependency lifecycle scripts. Flags
   * that merely constrain the lockfile ({@code --immutable}, {@code --frozen-lockfile}) do NOT
   * qualify and are handled by {@link #isLockfileMode} instead.
   */
  private static boolean hasIgnoreScripts(InstallInvocation inv) {
    String s = " " + inv.args() + " ";
    boolean explicitFlag = s.contains(" --ignore-scripts");
    boolean npmConfigEnv = isTruthy(inv.env().get("NPM_CONFIG_IGNORE_SCRIPTS"));
    return switch (inv.tool()) {
      case "npm", "pnpm" -> explicitFlag || npmConfigEnv;
      case "bun" -> explicitFlag;
        // yarn classic honours --ignore-scripts; yarn berry needs --mode=skip-build or
        // enableScripts=false supplied through YARN_ENABLE_SCRIPTS.
      case "yarn" ->
          explicitFlag
              || npmConfigEnv
              || s.contains(" --mode=skip-build")
              || isFalsy(inv.env().get("YARN_ENABLE_SCRIPTS"));
      default -> true; // unknown — let allowlist handle it
    };
  }

  private static boolean isLockfileMode(String segment, InstallInvocation inv) {
    String s = " " + inv.args() + " ";
    return switch (inv.tool) {
      case "npm" -> inv.verb.equals("ci") || s.contains(" --frozen-lockfile");
      case "bun" -> s.contains(" --frozen-lockfile");
      case "pnpm" -> s.contains(" --frozen-lockfile");
      case "yarn" -> s.contains(" --immutable") || s.contains(" --frozen-lockfile");
      default -> true;
    };
  }

  /**
   * RR-248 — an off-registry source is either a non-http scheme we never allow, or an http(s) URL
   * whose parsed HOST is not one of {@link #KNOWN_REGISTRY_HOSTS}. Matching on host (not on a
   * string prefix) is what makes {@code https://registry.npmjs.org.evil.example} and {@code
   * https://registry.npmjs.org@evil.example} the off-registry sources they actually are.
   *
   * <p>A URL that cannot be parsed, or that carries userinfo, is treated as off-registry — unknown
   * shapes fail closed rather than silently passing.
   */
  private static boolean containsOffRegistrySource(String segment) {
    if (OFF_REGISTRY_SCHEME.matcher(segment).find()) return true;
    var m = HTTP_URL.matcher(segment);
    while (m.find()) {
      if (!isKnownRegistryUrl(m.group())) return true;
    }
    return false;
  }

  private static boolean isKnownRegistryUrl(String url) {
    String host = hostOf(url);
    return host != null && KNOWN_REGISTRY_HOSTS.contains(host);
  }

  /**
   * Normalized lowercase host of an http(s) URL, or null when the URL is unparseable, is not
   * http(s), has no host, or carries userinfo (the {@code https://allowed@attacker} shape).
   */
  private static String hostOf(String url) {
    try {
      URI uri = new URI(url.trim());
      String scheme = uri.getScheme();
      if (scheme == null) return null;
      scheme = scheme.toLowerCase(Locale.ROOT);
      if (!scheme.equals("http") && !scheme.equals("https")) return null;
      if (uri.getUserInfo() != null) return null;
      // Reject anything URI parsing left in the raw authority that is not the host:port we read.
      String host = uri.getHost();
      if (host == null || host.isBlank()) return null;
      String authority = uri.getRawAuthority();
      if (authority != null && authority.contains("@")) return null;
      return host.toLowerCase(Locale.ROOT);
    } catch (URISyntaxException e) {
      return null;
    }
  }

  /** Scheme + host + effective port, so an allowlist entry is compared as an origin. */
  private static String originOf(String url) {
    try {
      URI uri = new URI(url.trim());
      String host = hostOf(url);
      if (host == null) return null;
      String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
      int port = uri.getPort();
      if (port < 0) port = scheme.equals("https") ? 443 : 80;
      return scheme + "://" + host + ":" + port;
    } catch (URISyntaxException e) {
      return null;
    }
  }

  private static String pathOf(String url) {
    try {
      String p = new URI(url.trim()).getPath();
      if (p == null || p.isBlank()) return "/";
      return p.endsWith("/") && p.length() > 1 ? p.substring(0, p.length() - 1) : p;
    } catch (URISyntaxException e) {
      return null;
    }
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
    String supplied = stripQuotes(m.group(1).trim());
    if (isAllowedRegistry(supplied, allowed)) return ValidationResult.allow();
    return block(
        GuardrailLevel.HIGH,
        "REGISTRY_NOT_ALLOWLISTED",
        "Registry "
            + supplied
            + " not in agent.security.deps.allowed-registries allowlist ("
            + allowed
            + "). Use one of the configured registries.");
  }

  /**
   * RR-248 — exact origin match (scheme + host + effective port) plus a path prefix taken on a
   * segment boundary. Fails closed when either side is unparseable, is not http(s), or carries
   * userinfo.
   */
  static boolean isAllowedRegistry(String supplied, List<String> allowed) {
    String suppliedOrigin = originOf(supplied);
    String suppliedPath = pathOf(supplied);
    if (suppliedOrigin == null || suppliedPath == null) return false;
    for (String a : allowed) {
      String allowedOrigin = originOf(a);
      String allowedPath = pathOf(a);
      if (allowedOrigin == null || allowedPath == null) continue;
      if (!allowedOrigin.equals(suppliedOrigin)) continue;
      if (allowedPath.equals("/")
          || suppliedPath.equals(allowedPath)
          || suppliedPath.startsWith(allowedPath + "/")) {
        return true;
      }
    }
    return false;
  }

  private List<String> parseAllowedRegistries() {
    if (allowedRegistriesCsv == null || allowedRegistriesCsv.isBlank()) return List.of();
    return List.of(allowedRegistriesCsv.split("\\s*,\\s*"));
  }

  private static String ignoreScriptsHint(String tool) {
    return switch (tool) {
      case "bun" -> "the `--ignore-scripts` flag";
      case "npm", "pnpm" -> "the `--ignore-scripts` flag (or NPM_CONFIG_IGNORE_SCRIPTS=1)";
        // RR-247: --immutable is a lockfile flag and is deliberately NOT listed here.
      case "yarn" ->
          "the `--ignore-scripts` flag (yarn classic) or `--mode=skip-build` /"
              + " `YARN_ENABLE_SCRIPTS=false` (yarn berry)";
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

  /**
   * RR-251 — the block MESSAGE embeds the raw command segment (and, for a registry block, the
   * supplied registry URL), which routinely carries auth tokens or credential-bearing URLs. Logging
   * that message verbatim at WARN leaked secrets into persistent application logs and downstream
   * observability. The message is still RETURNED to the worker (its own command, echoed back so it
   * can self-correct), but the log line now records only a stable rule id and the guardrail level —
   * never the raw command.
   */
  private ValidationResult block(GuardrailLevel level, String ruleId, String message) {
    log.warn("DEP-SECURITY BLOCKED guardrail={} rule={}", level, ruleId);
    if (guardrailLevelResolver != null) guardrailLevelResolver.recordBlock("deps");
    return ValidationResult.block(message);
  }
}
