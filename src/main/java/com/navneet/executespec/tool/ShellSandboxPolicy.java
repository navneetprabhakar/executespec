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

  @Autowired
  public ShellSandboxPolicy(GuardrailLevelResolver guardrailLevelResolver) {
    this.guardrailLevelResolver = guardrailLevelResolver;
  }

  /** Back-compat constructor — assumes LOW. */
  public ShellSandboxPolicy() {
    this.guardrailLevelResolver = null;
  }

  // Commands that are NEVER allowed regardless of context
  private static final Set<String> BLOCKED_COMMANDS =
      Set.of(
          "rm -rf /",
          "rm -rf /*",
          "mkfs",
          "dd if=",
          ":(){ :|:& };:",
          "chmod -R 777 /",
          "chown -R",
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
          Pattern.compile("\\bumount\\b"));

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
          "curl",
          "wget" // Allowed for local smoke tests — dangerous patterns (pipe to bash, upload, exfil)
          // are caught by BLOCKED_PATTERNS
          );

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
