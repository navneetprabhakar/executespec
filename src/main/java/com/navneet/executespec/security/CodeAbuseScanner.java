package com.navneet.executespec.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Scans agent-generated code for malicious patterns: crypto mining,
 * reverse shells, credential harvesting, and obfuscated payloads.
 *
 * <p>Integrated into FileSystemTool.writeFile() to flag (and optionally block)
 * dangerous content before it's written to disk.
 */
@Slf4j
@Component
public class CodeAbuseScanner {

    /** Patterns that indicate crypto mining activity. */
    private static final List<Pattern> MINING_PATTERNS = List.of(
        Pattern.compile("stratum\\+tcp://"),
        Pattern.compile("\\bxmrig\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bcoinhive\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bcryptonight\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bminergate\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bethminer\\b", Pattern.CASE_INSENSITIVE)
    );

    /** Patterns that indicate reverse shell or backdoor creation. */
    private static final List<Pattern> SHELL_PATTERNS = List.of(
        Pattern.compile("bash\\s+-i\\s+>&\\s+/dev/tcp/"),
        Pattern.compile("nc\\s+-e\\s+/bin/(sh|bash)"),
        Pattern.compile("\\bsocat\\b.*\\bexec\\b"),
        Pattern.compile("python.*socket\\..*connect.*\\bos\\.(dup2|popen)"),
        Pattern.compile("/dev/tcp/\\d+\\.\\d+\\.\\d+\\.\\d+"),
        Pattern.compile("\\bmkfifo\\b.*\\bnc\\b"),
        // PowerShell reverse shells
        Pattern.compile("\\bInvoke-Expression\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bNew-Object\\s+.*\\.Net\\.Sockets\\.TCPClient\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bIEX\\s*\\(", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bpowershell\\b.*-e(nc|ncodedcommand)?\\s+", Pattern.CASE_INSENSITIVE),
        // Bash variable indirection (obfuscation technique)
        Pattern.compile("\\$\\{!\\w+\\}")
    );

    /** Patterns that indicate data exfiltration. */
    private static final List<Pattern> EXFIL_PATTERNS = List.of(
        Pattern.compile("curl\\s+.*-d\\s+@/etc/passwd"),
        Pattern.compile("curl\\s+.*--data-binary\\s+@"),
        Pattern.compile("wget\\s+.*--post-file"),
        Pattern.compile("\\bfetch\\s*\\(.*document\\.cookie", Pattern.CASE_INSENSITIVE),
        // DNS exfiltration
        Pattern.compile("\\bdig\\b.*\\$\\("),
        Pattern.compile("\\bnslookup\\b.*\\$\\("),
        Pattern.compile("\\bburpcollaborator\\.net\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\binteractsh\\.com\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\boast\\.(fun|pro|me)\\b", Pattern.CASE_INSENSITIVE)
    );

    /** Patterns that indicate cloud metadata endpoint access. */
    private static final List<Pattern> METADATA_PATTERNS = List.of(
        Pattern.compile("169\\.254\\.169\\.254"),
        Pattern.compile("metadata\\.google\\.internal", Pattern.CASE_INSENSITIVE),
        Pattern.compile("metadata\\.azure\\.com", Pattern.CASE_INSENSITIVE),
        Pattern.compile("/latest/meta-data/")
    );

    /** Patterns that indicate obfuscated payloads. */
    private static final List<Pattern> OBFUSCATION_PATTERNS = List.of(
        Pattern.compile("eval\\s*\\(\\s*atob\\s*\\("),
        Pattern.compile("eval\\s*\\(\\s*Buffer\\.from\\s*\\("),
        Pattern.compile("eval\\s*\\(\\s*String\\.fromCharCode"),
        // Base64-encoded strings longer than 500 chars (likely embedded binary)
        Pattern.compile("[A-Za-z0-9+/]{500,}={0,2}")
    );

    public record ScanResult(boolean flagged, String category, String detail) {
        public static ScanResult clean() {
            return new ScanResult(false, null, null);
        }
        public static ScanResult flagged(String category, String detail) {
            return new ScanResult(true, category, detail);
        }
    }

    /**
     * Scans file content for malicious patterns.
     *
     * @param filePath the file being written (for logging)
     * @param content  the file content to scan
     * @return scan result indicating CLEAN or FLAGGED with details
     */
    public ScanResult scan(String filePath, String content) {
        if (content == null || content.isBlank()) return ScanResult.clean();

        for (Pattern p : MINING_PATTERNS) {
            if (p.matcher(content).find()) {
                log.warn("CODE ABUSE FLAGGED (mining): file={}, pattern={}", filePath, p.pattern());
                return ScanResult.flagged("CRYPTO_MINING", p.pattern());
            }
        }

        for (Pattern p : SHELL_PATTERNS) {
            if (p.matcher(content).find()) {
                log.warn("CODE ABUSE FLAGGED (reverse shell): file={}, pattern={}", filePath, p.pattern());
                return ScanResult.flagged("REVERSE_SHELL", p.pattern());
            }
        }

        for (Pattern p : EXFIL_PATTERNS) {
            if (p.matcher(content).find()) {
                log.warn("CODE ABUSE FLAGGED (exfiltration): file={}, pattern={}", filePath, p.pattern());
                return ScanResult.flagged("DATA_EXFILTRATION", p.pattern());
            }
        }

        for (Pattern p : METADATA_PATTERNS) {
            if (p.matcher(content).find()) {
                log.warn("CODE ABUSE FLAGGED (metadata access): file={}, pattern={}", filePath, p.pattern());
                return ScanResult.flagged("METADATA_ACCESS", p.pattern());
            }
        }

        for (Pattern p : OBFUSCATION_PATTERNS) {
            if (p.matcher(content).find()) {
                log.warn("CODE ABUSE FLAGGED (obfuscation): file={}, pattern={}", filePath, p.pattern());
                return ScanResult.flagged("OBFUSCATED_PAYLOAD", p.pattern());
            }
        }

        return ScanResult.clean();
    }
}
