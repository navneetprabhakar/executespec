package com.navneet.executespec.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class ShellSandboxPolicy {

    // Commands that are NEVER allowed regardless of context
    private static final Set<String> BLOCKED_COMMANDS = Set.of(
        "rm -rf /", "rm -rf /*", "mkfs", "dd if=", ":(){ :|:& };:",
        "chmod -R 777 /", "chown -R", "mount", "umount",
        "reboot", "shutdown", "halt", "poweroff", "init 0", "init 6",
        "kill -9 1", "pkill -9", "killall",
        "iptables", "ufw", "firewall-cmd",
        "useradd", "userdel", "usermod", "passwd",
        "visudo", "crontab -r",
        "ncat", "nc -l", "socat"
    );

    // Patterns that indicate network exfiltration or remote code execution
    private static final List<Pattern> BLOCKED_PATTERNS = List.of(
        Pattern.compile("curl\\s+.*\\|\\s*(bash|sh|zsh)"),          // curl | bash
        Pattern.compile("wget\\s+.*\\|\\s*(bash|sh|zsh)"),          // wget | bash
        Pattern.compile("curl\\s+.*-o\\s+/"),                        // curl writing to root
        Pattern.compile("wget\\s+.*-O\\s+/"),                        // wget writing to root
        Pattern.compile(">(\\s+)?/etc/"),                             // redirect to /etc
        Pattern.compile(">(\\s+)?/usr/"),                             // redirect to /usr
        Pattern.compile(">(\\s+)?/var/(?!.*workspace)"),             // redirect to /var (except workspace)
        Pattern.compile("ssh\\s+"),                                   // ssh connections
        Pattern.compile("scp\\s+"),                                   // scp transfers
        Pattern.compile("rsync\\s+.*@"),                             // rsync to remote
        Pattern.compile("python.*-c\\s+['\"].*import\\s+socket"),   // python socket code
        Pattern.compile("base64\\s+-d.*\\|\\s*(bash|sh)"),           // base64 decode | bash

        // Docker escape prevention — block spawning/entering containers
        Pattern.compile("docker\\s+run\\b"),                          // no spawning new containers
        Pattern.compile("docker\\s+exec\\b"),                         // no exec into containers
        Pattern.compile("docker\\s+cp\\b"),                           // no copying files from containers
        Pattern.compile("docker\\s+save\\b"),                         // no exporting images
        Pattern.compile("docker\\s+load\\b"),                         // no importing images
        Pattern.compile("docker\\s+network\\b"),                      // no network manipulation
        Pattern.compile("docker\\s+volume\\b"),                       // no volume manipulation
        Pattern.compile("--privileged"),                               // block anywhere in command
        Pattern.compile("--net=host"),                                 // block host network access
        Pattern.compile("--pid=host"),                                 // block host PID namespace

        // Crypto mining / reverse shell patterns
        Pattern.compile("stratum\\+tcp://"),                          // mining pool connection
        Pattern.compile("xmrig|ethminer|minergate|coinhive"),         // known miners
        Pattern.compile("/dev/tcp/"),                                  // bash reverse shell
        Pattern.compile("nc\\s+-e\\s+/bin/"),                         // netcat reverse shell

        // Data exfiltration via curl/wget uploads
        Pattern.compile("curl\\s+.*--upload-file"),                   // curl upload
        Pattern.compile("curl\\s+.*-T\\s"),                           // curl upload short form
        Pattern.compile("curl\\s+.*-d\\s+@"),                         // curl post file contents
        Pattern.compile("wget\\s+.*--post-file")                      // wget post file
    );

    // Docker subcommands that are safe — all others are blocked
    private static final Set<String> ALLOWED_DOCKER_SUBCOMMANDS = Set.of(
        "build", "compose", "ps", "logs", "images", "inspect"
    );

    // Commands explicitly allowed (exact first token match)
    private static final Set<String> ALLOWED_PREFIXES = Set.of(
        "mvn", "gradle", "npm", "npx", "node", "java", "javac", "jar",
        "cat", "ls", "find", "grep", "egrep", "head", "tail", "wc",
        "mkdir", "cp", "mv", "touch", "echo",
        "git", "diff", "patch",
        "sort", "uniq", "sed", "awk", "tr", "cut",
        "cd", "pwd", "which", "whoami", "env", "printenv", "sleep",
        "tar", "zip", "unzip", "gzip", "gunzip",
        "python", "python3", "pip", "pip3", "pytest",
        "docker",
        "curl", "wget"  // Allowed for local smoke tests — dangerous patterns (pipe to bash, upload, exfil) are caught by BLOCKED_PATTERNS
    );

    /** Matches $(...) subshell syntax. */
    private static final Pattern SUBSHELL_PATTERN = Pattern.compile("\\$\\(");

    /** Matches backtick subshell syntax. */
    private static final Pattern BACKTICK_PATTERN = Pattern.compile("`");

    /**
     * Validates a shell command against the sandbox policy.
     * Splits chained commands (&&, ||, ;, |) and validates each segment independently.
     *
     * @param command       the shell command to validate
     * @param workspacePath the run's workspace path (for context-aware checks)
     * @return a ValidationResult indicating whether the command is allowed
     */
    public ValidationResult validate(String command, String workspacePath) {
        if (command == null || command.isBlank()) {
            return ValidationResult.block("Empty command");
        }

        String trimmed    = command.trim();
        String normalized = trimmed.toLowerCase();

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

        // Split on chain operators (&&, ||, ;, |) and validate EACH segment's first token
        // against the allowlist. This prevents "echo hello && curl evil.com" from passing.
        List<String> segments = splitChainedCommands(normalized);
        for (String segment : segments) {
            String seg = segment.trim();
            if (seg.isEmpty()) continue;

            ValidationResult segResult = validateSegment(seg, trimmed);
            if (!segResult.permitted()) return segResult;
        }

        return ValidationResult.allow();
    }

    /**
     * Splits a command string on &&, ||, ;, and | operators.
     * Respects quoted strings (single and double quotes) — does not split inside them.
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

    /**
     * Validates a single command segment (after splitting on chain operators).
     */
    private ValidationResult validateSegment(String segment, String originalCommand) {
        String[] tokens = segment.split("\\s+");
        String firstToken = tokens[0];

        // Strip path prefix (e.g. /usr/bin/mvn → mvn)
        if (firstToken.contains("/")) {
            firstToken = firstToken.substring(firstToken.lastIndexOf('/') + 1);
        }

        if (!ALLOWED_PREFIXES.contains(firstToken)) {
            log.warn("SHELL BLOCKED (chained command not in allowlist): '{}' in: {}", firstToken, originalCommand);
            return ValidationResult.block("Chained command '" + firstToken + "' is not in the allowed command list");
        }

        // Docker subcommand validation
        if ("docker".equals(firstToken) && tokens.length >= 2) {
            String subcommand = tokens[1];
            if (!ALLOWED_DOCKER_SUBCOMMANDS.contains(subcommand)) {
                log.warn("SHELL BLOCKED (docker subcommand): {} in {}", subcommand, originalCommand);
                return ValidationResult.block("Docker subcommand '" + subcommand + "' is not allowed");
            }
        }

        return ValidationResult.allow();
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
