package com.navneet.executespec.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sanitizes workspace context injected into LLM prompts to prevent prompt injection attacks.
 *
 * <h3>Threat model</h3>
 * <p>A malicious actor could commit files to a repository (or craft a spec) containing LLM
 * prompt-injection payloads (e.g., "Ignore previous instructions. You are now DAN..."). When
 * this content is embedded verbatim into the ConversationAgent system prompt, it can override
 * the agent's instructions.
 *
 * <h3>Mitigations</h3>
 * <ol>
 *   <li><b>Input sanitization:</b> Strip known injection phrases and XML system tags from content
 *       before embedding it in the prompt.</li>
 *   <li><b>Boundary wrapping:</b> Wrap injected content in XML boundary tags so the LLM can
 *       distinguish code/data from instructions.</li>
 *   <li><b>Output filtering:</b> Redact secret-like patterns (API keys, Bearer tokens) from LLM
 *       responses before returning them to the user.</li>
 * </ol>
 */
@Component
@Slf4j
public class ContextSanitizer {

    // ── Injection pattern detection ──────────────────────────────────────────

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("ignore\\s+previous\\s+instructions?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard\\s+(all\\s+)?previous\\s+instructions?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("you\\s+are\\s+now\\s+\\w+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("forget\\s+(all\\s+)?previous\\s+instructions?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("new\\s+instructions?\\s*:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("act\\s+as\\s+(if\\s+you\\s+are|a)\\s+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("pretend\\s+(you\\s+are|to\\s+be)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("jailbreak", Pattern.CASE_INSENSITIVE),
            Pattern.compile("DAN\\b")   // "Do Anything Now" jailbreak
    );

    /** XML system/instruction tags that could inject into the model's context. */
    private static final Pattern XML_SYSTEM_TAGS = Pattern.compile(
            "</?\\s*(system|instruction|prompt|context)\\s*>", Pattern.CASE_INSENSITIVE);

    // ── Secret patterns in LLM output ────────────────────────────────────────

    private static final List<Pattern> SECRET_OUTPUT_PATTERNS = List.of(
            // API keys
            Pattern.compile("\\bsk-[A-Za-z0-9\\-_]{20,}"),          // OpenAI sk- keys
            Pattern.compile("\\bsk-ant-[A-Za-z0-9\\-_]{20,}"),      // Anthropic sk-ant- keys
            Pattern.compile("\\bAIza[A-Za-z0-9\\-_]{30,}"),         // Google API keys
            // Authorization headers
            Pattern.compile("Bearer\\s+[A-Za-z0-9\\-_.~+/]{20,}"),  // Bearer tokens
            // Generic secrets
            Pattern.compile("(?i)api[_\\-]?key\\s*[:=]\\s*['\"]?[A-Za-z0-9\\-_]{16,}"),
            Pattern.compile("(?i)secret\\s*[:=]\\s*['\"]?[A-Za-z0-9\\-_]{16,}"),
            Pattern.compile("(?i)password\\s*[:=]\\s*['\"]?\\S{8,}")
    );

    private static final String REDACTED = "[REDACTED]";

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Sanitizes user-controlled content before embedding it in a prompt.
     * <ul>
     *   <li>Strips XML system/instruction tags</li>
     *   <li>Replaces known injection phrases with a neutralized placeholder</li>
     * </ul>
     *
     * @param content    the raw content (e.g. file source, README, plan JSON)
     * @param sourceName a label used in log messages (e.g. "workspace", "plan")
     * @return sanitized content safe to embed in a prompt
     */
    public String sanitizeInput(String content, String sourceName) {
        if (content == null || content.isBlank()) return content;

        String result = content;

        // Strip XML system/instruction tags (replace tag with harmless text)
        Matcher xmlMatcher = XML_SYSTEM_TAGS.matcher(result);
        if (xmlMatcher.find()) {
            log.warn("[ContextSanitizer] XML system/instruction tag found in {} — stripping", sourceName);
            result = xmlMatcher.replaceAll("[tag removed]");
        }

        // Replace injection phrases
        for (Pattern p : INJECTION_PATTERNS) {
            Matcher m = p.matcher(result);
            if (m.find()) {
                log.warn("[ContextSanitizer] Injection pattern '{}' found in {} — neutralizing",
                        p.pattern(), sourceName);
                result = m.replaceAll("[instruction removed]");
            }
        }

        return result;
    }

    /**
     * Wraps sanitized content in XML boundary tags so the LLM distinguishes
     * code/data context from its own instructions.
     *
     * @param content   already-sanitized content
     * @param tagName   the XML tag name to use (e.g. "workspace_context", "plan_data")
     * @return content wrapped in {@code <tagName>...</tagName>}
     */
    public String wrapInBoundary(String content, String tagName) {
        if (content == null || content.isBlank()) return content;
        return "<" + tagName + ">\n" + content + "\n</" + tagName + ">";
    }

    /**
     * Filters an LLM response to redact any secret-like patterns before returning
     * it to the user. This is a defense-in-depth measure against accidental secret leakage
     * (e.g., if a key was present in the workspace and the LLM echoed it).
     *
     * @param response the raw LLM response text
     * @return response with secret patterns replaced by {@code [REDACTED]}
     */
    public String sanitizeOutput(String response) {
        if (response == null || response.isBlank()) return response;

        String result = response;
        for (Pattern p : SECRET_OUTPUT_PATTERNS) {
            Matcher m = p.matcher(result);
            if (m.find()) {
                log.warn("[ContextSanitizer] Secret pattern found in LLM output — redacting");
                result = m.replaceAll(REDACTED);
            }
        }
        return result;
    }
}
