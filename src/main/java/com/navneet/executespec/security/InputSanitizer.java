package com.navneet.executespec.security;

import java.text.Normalizer;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.security.guardrail.GuardrailLevel;
import com.navneet.executespec.security.guardrail.GuardrailLevelResolver;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import lombok.extern.slf4j.Slf4j;

/**
 * Scans user-submitted spec content for prompt injection, shell embedding, and abuse patterns
 * before it reaches the LLM pipeline.
 *
 * <p>Guardrail-level aware as of Plan 1 P2. {@link GuardrailLevel#LOW} is the default and matches
 * historical behaviour byte-for-byte; {@link GuardrailLevel#MEDIUM} adds an 8KB payload cap; {@link
 * GuardrailLevel#HIGH} further tightens the non-printable-character tolerance from 60% to 10%.
 */
@Slf4j
@Component
public class InputSanitizer {

  /** MEDIUM+: hard cap on request size (characters). */
  private static final int MEDIUM_PAYLOAD_CAP = 8_000;

  /** HIGH: tighter non-printable tolerance. */
  private static final double HIGH_NON_PRINTABLE_RATIO = 0.10;

  /** Resolver is optional to keep the no-args constructor viable for existing callers. */
  private final GuardrailLevelResolver guardrailLevelResolver;

  @Autowired
  public InputSanitizer(GuardrailLevelResolver guardrailLevelResolver) {
    this.guardrailLevelResolver = guardrailLevelResolver;
  }

  /** Back-compat constructor — assumes LOW level (current behaviour). */
  public InputSanitizer() {
    this.guardrailLevelResolver = null;
  }

  // Prompt injection patterns — attempts to override system instructions
  private static final List<Pattern> INJECTION_PATTERNS =
      List.of(
          Pattern.compile("ignore\\s+(all\\s+)?previous\\s+instructions", Pattern.CASE_INSENSITIVE),
          Pattern.compile("ignore\\s+(all\\s+)?above\\s+instructions", Pattern.CASE_INSENSITIVE),
          Pattern.compile("disregard\\s+(all\\s+)?previous", Pattern.CASE_INSENSITIVE),
          Pattern.compile("you\\s+are\\s+now\\s+(a|an)\\s+", Pattern.CASE_INSENSITIVE),
          Pattern.compile("new\\s+system\\s+prompt", Pattern.CASE_INSENSITIVE),
          Pattern.compile("<\\s*system\\s*>", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\bsystem\\s*:\\s*you\\s+are", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\[\\s*INST\\s*\\]", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\{\\{\\s*system", Pattern.CASE_INSENSITIVE),
          Pattern.compile("override\\s+(your|the)\\s+instructions", Pattern.CASE_INSENSITIVE),
          Pattern.compile(
              "forget\\s+(all\\s+)?(your|previous)\\s+(instructions|rules)",
              Pattern.CASE_INSENSITIVE));

  // Shell embedding patterns — attempts to inject executable scripts
  private static final List<Pattern> SHELL_PATTERNS =
      List.of(
          Pattern.compile("#!\\s*/bin/(bash|sh|zsh)"),
          Pattern.compile("#!\\s*/usr/bin/env\\s+(bash|sh|python|node)"),
          Pattern.compile("\\bcurl\\s+.*\\|\\s*(bash|sh)", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\bwget\\s+.*\\|\\s*(bash|sh)", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\beval\\s*\\(\\s*atob\\s*\\(", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\bbase64\\s+-d\\s*\\|\\s*(bash|sh)", Pattern.CASE_INSENSITIVE));

  // Abuse patterns — requests for clearly malicious software
  private static final List<Pattern> ABUSE_PATTERNS =
      List.of(
          Pattern.compile("\\b(crypto|bitcoin|monero)\\s*min(er|ing)\\b", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\bxmrig\\b", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\breverse\\s+shell\\b", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\bkeylog(ger|ging)\\b", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\bransom\\s*ware\\b", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\brat\\s+(trojan|malware|payload)\\b", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\bddos\\b", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\bphishing\\s+(page|site|kit)\\b", Pattern.CASE_INSENSITIVE),
          Pattern.compile("\\bcredential\\s+(stealer|harvester)\\b", Pattern.CASE_INSENSITIVE));

  /** Zero-width and invisible Unicode characters used to evade pattern matching. */
  private static final Pattern ZERO_WIDTH_CHARS =
      Pattern.compile("[\\u200B-\\u200F\\u2060\\uFEFF\\u00AD]");

  /** Base64 block detection — 100+ consecutive base64 chars may hide payload. */
  private static final Pattern BASE64_BLOCK = Pattern.compile("[A-Za-z0-9+/=]{100,}");

  /** Maximum allowed percentage of non-printable characters. */
  private static final double MAX_NON_PRINTABLE_RATIO = 0.6;

  /** Maximum times a 50-char substring can repeat (token-stuffing defense). */
  private static final int MAX_REPETITIONS = 20;

  /**
   * Scans the user request for malicious content using the guardrail level resolved from the {@code
   * security.guardrail_level} feature flag. Equivalent to {@code scan(userRequest,
   * resolver.current())}.
   */
  public void scan(String userRequest) {
    GuardrailLevel level =
        guardrailLevelResolver != null ? guardrailLevelResolver.current() : GuardrailLevel.LOW;
    scan(userRequest, level);
  }

  /**
   * Scans the user request at the specified guardrail level. LOW matches historical behaviour;
   * MEDIUM adds an 8KB payload cap; HIGH further tightens non-printable-character tolerance.
   *
   * @param userRequest the raw user spec
   * @param level security posture to apply
   * @throws ResponseStatusException 400 if content violates security policy
   */
  public void scan(String userRequest, GuardrailLevel level) {
    if (userRequest == null || userRequest.isBlank()) return;
    GuardrailLevel effective = level == null ? GuardrailLevel.LOW : level;

    // MEDIUM+: payload cap (before any expensive scanning).
    if (effective.atLeastMedium() && userRequest.length() > MEDIUM_PAYLOAD_CAP) {
      log.warn("INPUT BLOCKED (payload cap at {}): {} chars", effective, userRequest.length());
      if (guardrailLevelResolver != null) guardrailLevelResolver.recordBlock("input");
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Request exceeds the "
              + MEDIUM_PAYLOAD_CAP
              + "-character cap at guardrail level "
              + effective
              + ".");
    }

    // Strip zero-width characters before normalization — prevents evasion via invisible chars
    String stripped = ZERO_WIDTH_CHARS.matcher(userRequest).replaceAll("");

    // Unicode NFKC normalization — collapses homoglyphs (e.g., Cyrillic 'а' → Latin 'a',
    // fullwidth 'ｉｇｎｏｒｅ' → 'ignore') before pattern matching to prevent evasion.
    String normalized = Normalizer.normalize(stripped, Normalizer.Form.NFKC);

    // Check prompt injection, shell embedding, and abuse patterns
    scanPatterns(
        normalized,
        INJECTION_PATTERNS,
        "INPUT BLOCKED (prompt injection): pattern={}",
        "Request contains content that violates our security policy.");
    scanPatterns(
        normalized,
        SHELL_PATTERNS,
        "INPUT BLOCKED (shell embedding): pattern={}",
        "Request contains content that violates our security policy.");
    scanPatterns(
        normalized,
        ABUSE_PATTERNS,
        "INPUT BLOCKED (abuse): pattern={}",
        "Request describes software that violates our acceptable use policy.");

    // Base64 payload detection — decode and scan embedded base64 blocks
    Matcher b64Matcher = BASE64_BLOCK.matcher(normalized);
    while (b64Matcher.find()) {
      try {
        byte[] decoded = Base64.getDecoder().decode(b64Matcher.group());
        String decodedStr = new String(decoded);
        // Scan decoded content against all pattern lists
        scanPatterns(
            decodedStr,
            INJECTION_PATTERNS,
            "INPUT BLOCKED (base64 prompt injection): decoded content matched {}",
            "Request contains encoded content that violates our security policy.");
        scanPatterns(
            decodedStr,
            SHELL_PATTERNS,
            "INPUT BLOCKED (base64 shell embedding): decoded content matched {}",
            "Request contains encoded content that violates our security policy.");
        scanPatterns(
            decodedStr,
            ABUSE_PATTERNS,
            "INPUT BLOCKED (base64 abuse): decoded content matched {}",
            "Request contains encoded content that violates our acceptable use policy.");
      } catch (IllegalArgumentException ignored) {
        // Not valid base64 — skip
      }
    }

    // Character ratio check — high non-printable ratio indicates binary/obfuscation.
    // HIGH level tightens the tolerance from 60% to 10%.
    long nonPrintable =
        userRequest.chars().filter(c -> c < 32 && c != '\n' && c != '\r' && c != '\t').count();
    double nonPrintableCap =
        effective.atLeastHigh() ? HIGH_NON_PRINTABLE_RATIO : MAX_NON_PRINTABLE_RATIO;
    if ((double) nonPrintable / userRequest.length() > nonPrintableCap) {
      log.warn("INPUT BLOCKED (non-printable ratio): {}/{}", nonPrintable, userRequest.length());
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Request contains too many non-printable characters.");
    }

    // Repetition check — detects token-stuffing attacks
    if (userRequest.length() >= 1000) {
      for (int i = 0; i <= userRequest.length() - 50; i += 50) {
        String chunk = userRequest.substring(i, i + 50);
        int count = 0;
        int idx = 0;
        while ((idx = userRequest.indexOf(chunk, idx)) != -1) {
          count++;
          idx += 50;
          if (count > MAX_REPETITIONS) {
            log.warn(
                "INPUT BLOCKED (repetition attack): chunk repeated {}+ times", MAX_REPETITIONS);
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Request contains suspicious repetitive content.");
          }
        }
      }
    }
  }

  /**
   * Scans input against a list of patterns, blocking with a 400 if any match.
   *
   * @param input the text to scan
   * @param patterns compiled patterns to match against
   * @param logMessage log warning template (must contain one {} placeholder for the matched
   *     pattern)
   * @param userMessage error message returned to the client
   */
  private void scanPatterns(
      String input, List<Pattern> patterns, String logMessage, String userMessage) {
    for (Pattern p : patterns) {
      if (p.matcher(input).find()) {
        log.warn(logMessage, p.pattern());
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, userMessage);
      }
    }
  }
}
