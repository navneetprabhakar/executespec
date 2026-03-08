package com.navneet.agentteam.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
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
        Pattern.compile("base64\\s+-d.*\\|\\s*(bash|sh)")            // base64 decode | bash
    );

    // Commands explicitly allowed (exact first token match)
    private static final Set<String> ALLOWED_PREFIXES = Set.of(
        "mvn", "gradle", "npm", "npx", "node", "java", "javac", "jar",
        "cat", "ls", "find", "grep", "egrep", "head", "tail", "wc",
        "mkdir", "cp", "mv", "touch", "echo",
        "git", "diff", "patch",
        "sort", "uniq", "sed", "awk", "tr", "cut",
        "pwd", "which", "whoami", "env", "printenv", "sleep",
        "tar", "zip", "unzip", "gzip", "gunzip",
        "python", "python3", "pip", "pip3", "pytest",
        "docker"
    );

    /**
     * Validates a shell command against the sandbox policy.
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

        // Check explicit blocklist
        for (String blocked : BLOCKED_COMMANDS) {
            if (normalized.contains(blocked)) {
                log.warn("SHELL BLOCKED (explicit): {}", trimmed);
                return ValidationResult.block("Command contains blocked operation: " + blocked);
            }
        }

        // Check blocked patterns
        for (Pattern pattern : BLOCKED_PATTERNS) {
            if (pattern.matcher(normalized).find()) {
                log.warn("SHELL BLOCKED (pattern): {}", trimmed);
                return ValidationResult.block("Command matches blocked pattern");
            }
        }

        // Block absolute path navigation (cd /) — prevents jumping to system dirs.
        // Allow 'cd ..' since workers legitimately navigate subdirectories within their
        // worktree (e.g. 'cd src && cd .. && cd resources'). The real sandbox is the
        // process working directory set by ShellTool + FileSystemTool.safeResolve().
        if (normalized.matches(".*\\bcd\\s+/(?!\\.).*")) {
            log.warn("SHELL BLOCKED (absolute cd): {}", trimmed);
            return ValidationResult.block("Absolute directory navigation blocked (cd /)");
        }

        // Verify command starts with an allowed prefix
        String firstToken = normalized.split("\\s+")[0];
        // Strip path prefix (e.g. /usr/bin/mvn → mvn)
        if (firstToken.contains("/")) {
            firstToken = firstToken.substring(firstToken.lastIndexOf('/') + 1);
        }

        boolean prefixAllowed = ALLOWED_PREFIXES.contains(firstToken);
        if (!prefixAllowed) {
            log.warn("SHELL BLOCKED (not in allowlist): {}", trimmed);
            return ValidationResult.block("Command '" + firstToken + "' is not in the allowed command list");
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
