package com.navneet.executespec.security;

import java.nio.charset.StandardCharsets;
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
 * <p>Guardrail-level aware as of Plan 1 P2. Runtime resolution fails safe to {@link
 * GuardrailLevel#HIGH}; {@link GuardrailLevel#MEDIUM} adds an 8KB payload cap and HIGH further
 * tightens the non-printable-character tolerance from 60% to 10%.
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

  /** Back-compat/test constructor — fail-safe HIGH when no runtime resolver is available. */
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

  /** Bound recursive encoded-payload inspection so nested input cannot amplify without limit. */
  private static final int MAX_BASE64_DECODE_DEPTH = 3;

  /** Aggregate decoded bytes inspected across all Base64 blocks in one request. */
  private static final int MAX_BASE64_DECODED_BYTES = 32 * 1024;

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
        guardrailLevelResolver != null ? guardrailLevelResolver.current() : GuardrailLevel.HIGH;
    scan(userRequest, level);
  }

  /**
   * Return the canonical representation persisted by chat ingress. The scanner additionally folds a
   * narrow confusable set for matching only, so legitimate international text is not rewritten.
   */
  public String normalize(String userRequest) {
    if (userRequest == null || userRequest.isBlank()) return userRequest;
    String stripped = ZERO_WIDTH_CHARS.matcher(userRequest).replaceAll("");
    return Normalizer.normalize(stripped, Normalizer.Form.NFKC);
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
    GuardrailLevel effective = level == null ? GuardrailLevel.HIGH : level;

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
    // Unicode NFKC normalization + invisible-character removal must be identical to the value
    // chat ingress persists after this scan.
    String normalized = foldSecurityKeywordConfusables(normalize(userRequest));

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

    scanEncodedPayloads(normalized, 0, new int[] {0});

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
   * Recursively inspect embedded Base64 text with explicit depth and aggregate-byte limits. The
   * limit is fail-closed: accepting a deeper or larger encoded payload would recreate the bypass at
   * the first uninspected layer.
   */
  private void scanEncodedPayloads(String input, int depth, int[] decodedBytes) {
    Matcher matcher = BASE64_BLOCK.matcher(input);
    while (matcher.find()) {
      if (depth >= MAX_BASE64_DECODE_DEPTH) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Request contains excessively nested encoded content.");
      }
      byte[] decoded;
      try {
        decoded = Base64.getDecoder().decode(matcher.group());
      } catch (IllegalArgumentException invalidBase64) {
        continue;
      }
      if (decodedBytes[0] > MAX_BASE64_DECODED_BYTES - decoded.length) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Request contains too much encoded content.");
      }
      decodedBytes[0] += decoded.length;
      String decodedText =
          foldSecurityKeywordConfusables(normalize(new String(decoded, StandardCharsets.UTF_8)));
      scanPatterns(
          decodedText,
          INJECTION_PATTERNS,
          "INPUT BLOCKED (base64 prompt injection): decoded content matched {}",
          "Request contains encoded content that violates our security policy.");
      scanPatterns(
          decodedText,
          SHELL_PATTERNS,
          "INPUT BLOCKED (base64 shell embedding): decoded content matched {}",
          "Request contains encoded content that violates our security policy.");
      scanPatterns(
          decodedText,
          ABUSE_PATTERNS,
          "INPUT BLOCKED (base64 abuse): decoded content matched {}",
          "Request contains encoded content that violates our acceptable use policy.");
      scanEncodedPayloads(decodedText, depth + 1, decodedBytes);
    }
  }

  /**
   * Fold only the small Greek/Cyrillic lookalike set useful for security-keyword evasion. This is
   * deliberately a scan-only representation, not general transliteration: benign international text
   * is retained unchanged by {@link #normalize(String)}.
   */
  private static String foldSecurityKeywordConfusables(String input) {
    if (input == null || input.isEmpty()) return input;
    StringBuilder folded = new StringBuilder(input.length());
    input
        .codePoints()
        .forEach(
            codePoint -> {
              int replacement =
                  switch (codePoint) {
                    case 0x0410, 0x0391 -> 'A'; // Cyrillic/Greek A
                    case 0x0430, 0x03B1 -> 'a';
                    case 0x0412, 0x0392 -> 'B';
                    case 0x0432, 0x03B2 -> 'b';
                    case 0x0415, 0x0395 -> 'E';
                    case 0x0435, 0x03B5 -> 'e';
                    case 0x0406, 0x0399 -> 'I';
                    case 0x0456, 0x03B9 -> 'i';
                    case 0x0408 -> 'J';
                    case 0x0458 -> 'j';
                    case 0x041A, 0x039A -> 'K';
                    case 0x043A, 0x03BA -> 'k';
                    case 0x041C, 0x039C -> 'M';
                    case 0x043C, 0x03BC -> 'm';
                    case 0x041D, 0x039D -> 'H';
                    case 0x043D, 0x03BD -> 'n';
                    case 0x041E, 0x039F -> 'O';
                    case 0x043E, 0x03BF -> 'o';
                    case 0x0420, 0x03A1 -> 'P';
                    case 0x0440, 0x03C1 -> 'p';
                    case 0x0421 -> 'C';
                    case 0x0441 -> 'c';
                    case 0x0405 -> 'S';
                    case 0x0455 -> 's';
                    case 0x0422, 0x03A4 -> 'T';
                    case 0x0442, 0x03C4 -> 't';
                    case 0x0425, 0x03A7 -> 'X';
                    case 0x0445, 0x03C7 -> 'x';
                    case 0x0423, 0x03A5 -> 'Y';
                    case 0x0443, 0x03C5 -> 'y';
                    default -> codePoint;
                  };
              folded.appendCodePoint(replacement);
            });
    return folded.toString();
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
