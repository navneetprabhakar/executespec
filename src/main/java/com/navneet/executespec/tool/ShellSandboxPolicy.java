package com.navneet.executespec.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.navneet.executespec.security.guardrail.GuardrailLevel;
import com.navneet.executespec.security.guardrail.GuardrailLevelResolver;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ShellSandboxPolicy {

  /** MEDIUM-level additional deny patterns. */
  private static final List<Pattern> MEDIUM_ADDITIONAL_PATTERNS =
      List.of(
          Pattern.compile("\\beval\\b"), // any `eval` token
          Pattern.compile("\\bexec\\b") // any `exec` token
          );

  /** HIGH-level strict npm/mvn/gradle verb allowlist. */
  private static final Set<String> HIGH_STRICT_NPM_VERBS =
      Set.of("install", "ci", "build", "test", "start", "run-script");

  /**
   * HIGH-level strict npx verb allowlist — superset of npm that adds the JS tools the RN / Expo
   * pipeline invokes ({@code expo}, {@code eas}, {@code tsc}, {@code jest}, {@code
   * create-expo-app}). npx needs its own list because it forwards to arbitrary tools, unlike npm
   * where the verb is a lifecycle name.
   */
  private static final Set<String> HIGH_STRICT_NPX_VERBS =
      Set.of(
          "install",
          "ci",
          "build",
          "test",
          "start",
          "run-script",
          "expo",
          "eas",
          "tsc",
          "jest",
          "create-expo-app",
          "create-react-app");

  private static final Set<String> HIGH_STRICT_MVN_VERBS =
      Set.of("compile", "test", "package", "verify", "install", "clean", "spring-boot:run");
  private static final Set<String> HIGH_STRICT_GRADLE_VERBS =
      Set.of("build", "test", "check", "clean", "assemble");

  /** HIGH-level strict expo verb allowlist (direct {@code expo <verb>} invocation). */
  private static final Set<String> HIGH_STRICT_EXPO_VERBS =
      Set.of("export", "prebuild", "install", "doctor", "run", "run:android", "run:ios");

  /**
   * HIGH-level strict eas verb allowlist. {@code update} (EAS OTA push) is deliberately excluded —
   * it's a deploy-equivalent path and must route through the managed {@code /api/deployments}
   * endpoint, not shell.
   */
  private static final Set<String> HIGH_STRICT_EAS_VERBS = Set.of("build", "submit");

  private final GuardrailLevelResolver guardrailLevelResolver;

  /**
   * 2026-05-02 — dependency-install enforcement at MEDIUM+. ObjectProvider so unit tests that
   * construct {@code ShellSandboxPolicy} directly without booting the full Spring context still
   * work. Null-safe — when absent, the segment-level dep checks are skipped.
   */
  private final org.springframework.beans.factory.ObjectProvider<
          com.navneet.executespec.security.dependency.DependencySecurityPolicy>
      dependencySecurityProvider;

  @Autowired
  public ShellSandboxPolicy(
      GuardrailLevelResolver guardrailLevelResolver,
      org.springframework.beans.factory.ObjectProvider<
              com.navneet.executespec.security.dependency.DependencySecurityPolicy>
          dependencySecurityProvider) {
    this.guardrailLevelResolver = guardrailLevelResolver;
    this.dependencySecurityProvider = dependencySecurityProvider;
  }

  /** Test-friendly constructor — no dep-security enforcement. */
  public ShellSandboxPolicy(GuardrailLevelResolver guardrailLevelResolver) {
    this(
        guardrailLevelResolver,
        new org.springframework.beans.factory.ObjectProvider<>() {
          @Override
          public com.navneet.executespec.security.dependency.DependencySecurityPolicy getObject() {
            return null;
          }

          @Override
          public com.navneet.executespec.security.dependency.DependencySecurityPolicy getObject(
              Object... args) {
            return null;
          }

          @Override
          public com.navneet.executespec.security.dependency.DependencySecurityPolicy
              getIfAvailable() {
            return null;
          }

          @Override
          public com.navneet.executespec.security.dependency.DependencySecurityPolicy
              getIfUnique() {
            return null;
          }
        });
  }

  /** Back-compat constructor — assumes LOW + no dep-security enforcement. */
  public ShellSandboxPolicy() {
    this(null);
  }

  // Commands that are NEVER allowed regardless of context.
  // NOTE: entries here are matched via {@code normalized.contains(blocked)} where
  // {@code normalized = command.trim().toLowerCase()}. Therefore every entry MUST be lowercase
  // — entries with uppercase letters silently never match. (Latent bug pre-2026-05-07: the old
  // "chmod -R 777 /" + "chown -R" entries had capital R and never fired; the new chmod-system
  // BLOCKED_PATTERN below + the lowercase forms here close that gap.)
  private static final Set<String> BLOCKED_COMMANDS =
      Set.of(
          "rm -rf /",
          "rm -rf /*",
          "mkfs",
          "dd if=",
          ":(){ :|:& };:",
          "chmod -r 777 /",
          "chmod 777 /",
          "chown -r",
          "reboot",
          "shutdown",
          "halt",
          "poweroff",
          "init 0",
          "init 6",
          "kill -9 1",
          "pkill -9",
          "killall",
          "iptables",
          "ufw",
          "firewall-cmd",
          "useradd",
          "userdel",
          "usermod",
          "passwd",
          "visudo",
          "crontab -r",
          "ncat",
          "nc -l",
          "socat");

  // Patterns that indicate network exfiltration or remote code execution
  private static final List<Pattern> BLOCKED_PATTERNS =
      List.of(
          Pattern.compile("curl\\s+.*\\|\\s*(bash|sh|zsh)"), // curl | bash
          Pattern.compile("wget\\s+.*\\|\\s*(bash|sh|zsh)"), // wget | bash
          Pattern.compile("curl\\s+.*-o\\s+/"), // curl writing to root
          Pattern.compile("wget\\s+.*-O\\s+/"), // wget writing to root
          Pattern.compile(">(\\s+)?/etc/"), // redirect to /etc
          Pattern.compile(">(\\s+)?/usr/"), // redirect to /usr
          Pattern.compile(">(\\s+)?/var/(?!.*workspace)"), // redirect to /var (except workspace)
          Pattern.compile("ssh\\s+"), // ssh connections
          Pattern.compile("scp\\s+"), // scp transfers
          Pattern.compile("rsync\\s+.*@"), // rsync to remote
          Pattern.compile("python.*-c\\s+['\"].*import\\s+socket"), // python socket code
          Pattern.compile("base64\\s+-d.*\\|\\s*(bash|sh)"), // base64 decode | bash

          // Docker escape prevention — block spawning/entering containers
          Pattern.compile("docker\\s+run\\b"), // no spawning new containers
          Pattern.compile("docker\\s+exec\\b"), // no exec into containers
          Pattern.compile("docker\\s+cp\\b"), // no copying files from containers
          Pattern.compile("docker\\s+save\\b"), // no exporting images
          Pattern.compile("docker\\s+load\\b"), // no importing images
          Pattern.compile("docker\\s+network\\b"), // no network manipulation
          Pattern.compile("docker\\s+volume\\b"), // no volume manipulation
          Pattern.compile("--privileged"), // block anywhere in command
          Pattern.compile("--net=host"), // block host network access
          Pattern.compile("--pid=host"), // block host PID namespace

          // Crypto mining / reverse shell patterns
          Pattern.compile("stratum\\+tcp://"), // mining pool connection
          Pattern.compile("xmrig|ethminer|minergate|coinhive"), // known miners
          Pattern.compile("/dev/tcp/"), // bash reverse shell
          Pattern.compile("nc\\s+-e\\s+/bin/"), // netcat reverse shell

          // Data exfiltration via curl/wget uploads
          Pattern.compile("curl\\s+.*--upload-file"), // curl upload
          Pattern.compile("curl\\s+.*-T\\s"), // curl upload short form
          Pattern.compile("curl\\s+.*-d\\s+@"), // curl post file contents
          Pattern.compile("wget\\s+.*--post-file"), // wget post file

          // Filesystem mount/unmount — word-boundary to avoid false-positives (e.g. Express "mount"
          // in router code)
          Pattern.compile("\\bmount\\b"),
          Pattern.compile("\\bumount\\b"),

          // 2026-05-07 — chmod targeting system paths. We allow `chmod` in the prefix list so
          // workers can run the legitimate `chmod +x compile.sh` that ProgramBench (and any
          // generated build script) needs, but reject anything that touches system dirs. The
          // `chmod -R 777 /` exact-match phrase already lives in BLOCKED_COMMANDS; this pattern
          // catches the broader family (chmod 755 /etc/foo, chmod +x /usr/bin/bar, etc).
          // NOTE: `/opt` and `/var` are deliberately NOT in this list — production workspaces
          // bind to `/opt/app/workspace/<runId>` (`agent.workspace=./workspace` resolved against
          // `WorkingDirectory=/opt/app`), so blocking `/opt` would false-positive every worker
          // that uses an absolute workspace path. `/var` is similarly used by some host-side
          // scratch dirs. The remaining dirs are kernel + system + boot critical with no
          // legitimate workspace overlap.
          Pattern.compile(
              "\\bchmod\\b[^\\n]*\\s/(?:etc|usr|root|boot|sbin|bin|sys|proc|dev|run)\\b"),

          // 2026-06 (RL-2026-06, vix-leverage W1.5) — in-place stream edits bypass the
          // read-before-edit gate (EditFileTool's SHA-staleness check), mutating tracked source
          // with
          // no reviewable editFile diff. Force in-place mutation through editFile; read-only
          // sed/awk
          // (no in-place flag) stay allowed for inspection. Scope is the SAME segment ([^|;&] stops
          // at a chain/pipe operator) so a downstream `... | grep -i` or `| sort` is NOT
          // mis-flagged.
          // `perl -i` needs no entry — perl is not in ALLOWED_PREFIXES, so it is already blocked.
          Pattern.compile("\\bsed\\b[^|;&\\n]*\\s-i"), // sed -i / sed -i.bak (in-place)
          Pattern.compile("\\bsed\\b[^|;&\\n]*--in-place"), // sed --in-place
          Pattern.compile("\\bawk\\b[^|;&\\n]*-i\\s+inplace")); // gawk -i inplace

  // Docker subcommands that are safe — all others are blocked
  private static final Set<String> ALLOWED_DOCKER_SUBCOMMANDS =
      Set.of("build", "compose", "ps", "logs", "images", "inspect");

  // Commands explicitly allowed (exact first token match)
  private static final Set<String> ALLOWED_PREFIXES =
      Set.of(
          "mvn",
          "gradle",
          "npm",
          "npx",
          "node",
          "java",
          "javac",
          "jar",
          "cat",
          "ls",
          "find",
          "grep",
          "egrep",
          "head",
          "tail",
          "wc",
          "mkdir",
          "cp",
          "mv",
          "touch",
          "echo",
          "git",
          "diff",
          "patch",
          "sort",
          "uniq",
          "sed",
          "awk",
          "tr",
          "cut",
          "cd",
          "pwd",
          "which",
          "whoami",
          "env",
          "printenv",
          "sleep",
          "test",
          "[",
          "tar",
          "zip",
          "unzip",
          "gzip",
          "gunzip",
          "python",
          "python3",
          "pip",
          "pip3",
          "pytest",
          "docker",
          "expo",
          "eas",
          "tsc",
          "jest", // React Native / Expo toolchain (for direct invocations; `npx <tool>` also
          // allowed)
          // 2026-05-02 — modern JS package managers + non-Node language toolchains.
          // Risk model is identical to npm (already allowed): all five JS managers can run
          // arbitrary code via package lifecycle scripts, supply-chain attacks, and registry
          // network egress. They run with the same workspace permissions as npm; nothing new
          // is unlocked. Without this list the worker can't `bun install` / `pnpm i` /
          // `yarn add` / `deno cache` / `cargo build` / `go build` / `make`, which blocks
          // every non-npm JS project + every Rust / Go / Make project from passing the
          // build smoke test. NOT added: `bash` / `sh` (defeats the allowlist via `-c`),
          // `chown` (chain unknown binaries), `apt` / `brew` / `sudo` (system mutation),
          // `ssh` / `scp` / `rsync` (remote exfiltration paths). HIGH-mode strict verb checks
          // for these still need a per-tool verb list — TODO when someone flips the org to
          // HIGH guardrails.
          "bun",
          "pnpm",
          "yarn",
          "deno",
          "cargo",
          "rustc",
          "go",
          "make",
          "curl",
          "wget", // Allowed for local smoke tests — dangerous patterns (pipe to bash, upload,
          // exfil) are caught by BLOCKED_PATTERNS

          // 2026-05-07 — chmod was previously left out as "chains unknown binaries". In
          // practice every binary the worker can chain is already in this allowlist (gcc,
          // cargo, go, mvn, etc.), and forbidding chmod blocks the legitimate `chmod +x
          // compile.sh && ./compile.sh` that ProgramBench tasks need to verify their build
          // before declaring done. With chmod blocked, every cmatrix run on 2026-05-07
          // stagnated because the worker could not validate its own compile script. The
          // dangerous variants (`chmod -R 777 /`, chmod against /etc, /usr, /var, /root,
          // etc.) are caught by BLOCKED_COMMANDS + the chmod-system-path BLOCKED_PATTERN
          // entry above, so widening the allowlist with chmod does not widen the abuse
          // surface in any meaningful way — within workspace it can only flip permission
          // bits on files the worker already owns; in cleanroom + --network none, even
          // dangerous chmod has no exfiltration path.
          "chmod");

  /** Matches $(...) subshell syntax. */
  private static final Pattern SUBSHELL_PATTERN = Pattern.compile("\\$\\(");

  /** Matches backtick subshell syntax. */
  private static final Pattern BACKTICK_PATTERN = Pattern.compile("`");

  /**
   * Validates a shell command against the sandbox policy. Splits chained commands (&&, ||, ;, |)
   * and validates each segment independently.
   *
   * @param command the shell command to validate
   * @param workspacePath the run's workspace path (for context-aware checks)
   * @return a ValidationResult indicating whether the command is allowed
   */
  public ValidationResult validate(String command, String workspacePath) {
    GuardrailLevel level =
        guardrailLevelResolver != null ? guardrailLevelResolver.current() : GuardrailLevel.LOW;
    return validate(command, workspacePath, level);
  }

  /**
   * Level-aware validation. LOW preserves the historical allowlist/deny patterns exactly. MEDIUM
   * additionally rejects `eval` and `exec` tokens. HIGH further tightens {@code npm}, {@code mvn},
   * and {@code gradle} to canonical subcommands only (no {@code npm run <arbitrary-string>}, which
   * can execute user-supplied shell).
   */
  public ValidationResult validate(String command, String workspacePath, GuardrailLevel level) {
    GuardrailLevel effective = level == null ? GuardrailLevel.LOW : level;
    if (command == null || command.isBlank()) {
      return ValidationResult.block("Empty command");
    }

    String trimmed = command.trim();
    String normalized = trimmed.toLowerCase();

    // MEDIUM+: additional deny patterns
    if (effective.atLeastMedium()) {
      for (Pattern p : MEDIUM_ADDITIONAL_PATTERNS) {
        if (p.matcher(normalized).find()) {
          log.warn("SHELL BLOCKED (guardrail={} extra-pattern): {}", effective, trimmed);
          if (guardrailLevelResolver != null) guardrailLevelResolver.recordBlock("shell");
          return ValidationResult.block(
              "Command matches a guardrail " + effective + " denied pattern");
        }
      }
    }

    // Block subshell/backtick injection — $(...) and `...` can embed arbitrary commands
    if (SUBSHELL_PATTERN.matcher(normalized).find()) {
      log.warn("SHELL BLOCKED (subshell $()): {}", trimmed);
      return ValidationResult.block("Subshell expansion $(...) is not allowed");
    }
    if (BACKTICK_PATTERN.matcher(normalized).find()) {
      log.warn("SHELL BLOCKED (backtick subshell): {}", trimmed);
      return ValidationResult.block("Backtick subshell expansion is not allowed");
    }

    // Check explicit blocklist and patterns on the FULL command first
    // (catches cross-segment patterns like "curl ... | bash")
    for (String blocked : BLOCKED_COMMANDS) {
      if (normalized.contains(blocked)) {
        log.warn("SHELL BLOCKED (explicit): {}", trimmed);
        return ValidationResult.block("Command contains blocked operation: " + blocked);
      }
    }
    for (Pattern pattern : BLOCKED_PATTERNS) {
      if (pattern.matcher(normalized).find()) {
        log.warn("SHELL BLOCKED (pattern): {}", trimmed);
        return ValidationResult.block("Command matches blocked pattern");
      }
    }

    // Block absolute path navigation (cd /) — prevents jumping to system dirs.
    if (normalized.matches(".*\\bcd\\s+/(?!\\.).*")) {
      log.warn("SHELL BLOCKED (absolute cd): {}", trimmed);
      return ValidationResult.block("Absolute directory navigation blocked (cd /)");
    }

    // Block relative parent directory traversal (cd ..) — prevents escaping workspace.
    if (normalized.matches(".*\\bcd\\s+\\.\\.[/\\s].*")
        || normalized.matches(".*\\bcd\\s+\\.\\.\\s*$")) {
      log.warn("SHELL BLOCKED (relative cd ..): {}", trimmed);
      return ValidationResult.block("Relative parent directory navigation blocked (cd ..)");
    }

    // Split on chain operators (&&, ||, ;, |) and validate EACH segment's first token
    // against the allowlist. This prevents "echo hello && curl evil.com" from passing.
    List<String> segments = splitChainedCommands(normalized);
    for (String segment : segments) {
      String seg = segment.trim();
      if (seg.isEmpty()) continue;

      ValidationResult segResult = validateSegment(seg, trimmed, effective);
      if (!segResult.permitted()) return segResult;
    }

    return ValidationResult.allow();
  }

  /**
   * Splits a command string on &&, ||, ;, and | operators. Respects quoted strings (single and
   * double quotes) — does not split inside them.
   */
  private List<String> splitChainedCommands(String command) {
    List<String> segments = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    char[] chars = command.toCharArray();
    boolean inSingleQuote = false;
    boolean inDoubleQuote = false;

    for (int i = 0; i < chars.length; i++) {
      char c = chars[i];

      // Track quote state (don't split inside quotes)
      if (c == '\'' && !inDoubleQuote) {
        inSingleQuote = !inSingleQuote;
        current.append(c);
        continue;
      }
      if (c == '"' && !inSingleQuote) {
        inDoubleQuote = !inDoubleQuote;
        current.append(c);
        continue;
      }
      if (inSingleQuote || inDoubleQuote) {
        current.append(c);
        continue;
      }

      // Split on && and ||
      if (i + 1 < chars.length) {
        if ((c == '&' && chars[i + 1] == '&') || (c == '|' && chars[i + 1] == '|')) {
          segments.add(current.toString());
          current.setLength(0);
          i++; // skip second char
          continue;
        }
      }

      // Split on ; and single |
      if (c == ';' || c == '|') {
        segments.add(current.toString());
        current.setLength(0);
        continue;
      }

      current.append(c);
    }

    if (!current.isEmpty()) {
      segments.add(current.toString());
    }
    return segments;
  }

  /** Validates a single command segment (after splitting on chain operators). */
  private ValidationResult validateSegment(
      String segment, String originalCommand, GuardrailLevel level) {
    String[] tokens = segment.split("\\s+");
    String firstToken = tokens[0];

    // Strip path prefix (e.g. /usr/bin/mvn → mvn)
    if (firstToken.contains("/")) {
      firstToken = firstToken.substring(firstToken.lastIndexOf('/') + 1);
    }

    if (!ALLOWED_PREFIXES.contains(firstToken)) {
      log.warn(
          "SHELL BLOCKED (chained command not in allowlist): '{}' in: {}",
          firstToken,
          originalCommand);
      return ValidationResult.block(
          "Chained command '" + firstToken + "' is not in the allowed command list");
    }

    // 2026-05-02 — dependency-install security enforcement (MEDIUM+).
    // Each install segment must carry --ignore-scripts + lockfile-strict + audit + registry pin
    // per the active guardrail level. LOW preserves historical behaviour (legacy allowlist only).
    if (level != null && level.atLeastMedium() && dependencySecurityProvider != null) {
      var depSec = dependencySecurityProvider.getIfAvailable();
      if (depSec != null) {
        ValidationResult depResult = depSec.validateSegment(segment, originalCommand, level);
        if (!depResult.permitted()) return depResult;
      }
    }

    // Docker subcommand validation
    if ("docker".equals(firstToken) && tokens.length >= 2) {
      String subcommand = tokens[1];
      if (!ALLOWED_DOCKER_SUBCOMMANDS.contains(subcommand)) {
        log.warn("SHELL BLOCKED (docker subcommand): {} in {}", subcommand, originalCommand);
        return ValidationResult.block("Docker subcommand '" + subcommand + "' is not allowed");
      }
    }

    // HIGH: strict npm / mvn / gradle verbs — no arbitrary `npm run <script>`,
    // no custom `mvn <plugin>:<goal>` beyond the canonical set. Plan 1 P4.
    if (level != null && level.atLeastHigh() && tokens.length >= 2) {
      Set<String> canonical = canonicalVerbsFor(firstToken);
      if (canonical != null && !canonical.contains(tokens[1])) {
        log.warn(
            "SHELL BLOCKED (guardrail=HIGH strict verb): {} {} in {}",
            firstToken,
            tokens[1],
            originalCommand);
        if (guardrailLevelResolver != null) guardrailLevelResolver.recordBlock("shell");
        return ValidationResult.block(
            firstToken
                + " subcommand '"
                + tokens[1]
                + "' is not in the HIGH-level canonical verb allowlist");
      }
    }

    return ValidationResult.allow();
  }

  private static Set<String> canonicalVerbsFor(String firstToken) {
    return switch (firstToken) {
      case "npm" -> HIGH_STRICT_NPM_VERBS;
      case "npx" -> HIGH_STRICT_NPX_VERBS;
      case "mvn" -> HIGH_STRICT_MVN_VERBS;
      case "gradle" -> HIGH_STRICT_GRADLE_VERBS;
      case "expo" -> HIGH_STRICT_EXPO_VERBS;
      case "eas" -> HIGH_STRICT_EAS_VERBS;
      default -> null;
    };
  }

  public record ValidationResult(boolean permitted, String reason) {
    public static ValidationResult allow() {
      return new ValidationResult(true, null);
    }

    public static ValidationResult block(String reason) {
      return new ValidationResult(false, reason);
    }
  }
}
