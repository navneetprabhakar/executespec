package com.navneet.executespec.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Scans user-submitted spec content for prompt injection, shell embedding,
 * and abuse patterns before it reaches the LLM pipeline.
 */
@Slf4j
@Component
public class InputSanitizer {

    // Prompt injection patterns — attempts to override system instructions
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
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
        Pattern.compile("forget\\s+(all\\s+)?(your|previous)\\s+(instructions|rules)", Pattern.CASE_INSENSITIVE)
    );

    // Shell embedding patterns — attempts to inject executable scripts
    private static final List<Pattern> SHELL_PATTERNS = List.of(
        Pattern.compile("#!\\s*/bin/(bash|sh|zsh)"),
        Pattern.compile("#!\\s*/usr/bin/env\\s+(bash|sh|python|node)"),
        Pattern.compile("\\bcurl\\s+.*\\|\\s*(bash|sh)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bwget\\s+.*\\|\\s*(bash|sh)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\beval\\s*\\(\\s*atob\\s*\\(", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bbase64\\s+-d\\s*\\|\\s*(bash|sh)", Pattern.CASE_INSENSITIVE)
    );

    // Abuse patterns — requests for clearly malicious software
    private static final List<Pattern> ABUSE_PATTERNS = List.of(
        Pattern.compile("\\b(crypto|bitcoin|monero)\\s*min(er|ing)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bxmrig\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\breverse\\s+shell\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bkeylog(ger|ging)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bransom\\s*ware\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\brat\\s+(trojan|malware|payload)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bddos\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bphishing\\s+(page|site|kit)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bcredential\\s+(stealer|harvester)\\b", Pattern.CASE_INSENSITIVE)
    );

    /** Maximum allowed percentage of non-printable characters. */
    private static final double MAX_NON_PRINTABLE_RATIO = 0.6;

    /** Maximum times a 50-char substring can repeat (token-stuffing defense). */
    private static final int MAX_REPETITIONS = 20;

    /**
     * Scans the user request for malicious content.
     *
     * @param userRequest the raw user spec
     * @throws ResponseStatusException 400 if content violates security policy
     */
    public void scan(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return;

        // Unicode NFKC normalization — collapses homoglyphs (e.g., Cyrillic 'а' → Latin 'a',
        // fullwidth 'ｉｇｎｏｒｅ' → 'ignore') before pattern matching to prevent evasion.
        String normalized = Normalizer.normalize(userRequest, Normalizer.Form.NFKC);

        // Check prompt injection patterns
        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(normalized).find()) {
                log.warn("INPUT BLOCKED (prompt injection): pattern={}", p.pattern());
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Request contains content that violates our security policy.");
            }
        }

        // Check shell embedding patterns
        for (Pattern p : SHELL_PATTERNS) {
            if (p.matcher(normalized).find()) {
                log.warn("INPUT BLOCKED (shell embedding): pattern={}", p.pattern());
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Request contains content that violates our security policy.");
            }
        }

        // Check abuse patterns
        for (Pattern p : ABUSE_PATTERNS) {
            if (p.matcher(normalized).find()) {
                log.warn("INPUT BLOCKED (abuse): pattern={}", p.pattern());
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Request describes software that violates our acceptable use policy.");
            }
        }

        // Character ratio check — high non-printable ratio indicates binary/obfuscation
        long nonPrintable = userRequest.chars()
                .filter(c -> c < 32 && c != '\n' && c != '\r' && c != '\t')
                .count();
        if ((double) nonPrintable / userRequest.length() > MAX_NON_PRINTABLE_RATIO) {
            log.warn("INPUT BLOCKED (non-printable ratio): {}/{}", nonPrintable, userRequest.length());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Request contains too many non-printable characters.");
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
                        log.warn("INPUT BLOCKED (repetition attack): chunk repeated {}+ times", MAX_REPETITIONS);
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Request contains suspicious repetitive content.");
                    }
                }
            }
        }
    }
}
