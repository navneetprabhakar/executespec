package com.navneet.agentteam.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Centralises extraction of JSON payloads from raw LLM output.
 *
 * <p>LLMs frequently wrap their JSON responses in Markdown code fences
 * (e.g. {@code ```json ... ```}) or precede them with prose. This utility
 * strips those decorations before Jackson deserialisation, preventing the
 * duplicate {@code replaceAll} calls that previously existed in every agent.
 *
 * <h3>Extraction algorithm</h3>
 * <ol>
 *   <li>Strip leading/trailing whitespace.</li>
 *   <li>Remove an optional {@code ```json} (or plain {@code ```}) code fence.</li>
 *   <li>Find the outermost {@code { ... }} JSON object boundaries.</li>
 *   <li>Return the trimmed substring.</li>
 * </ol>
 * If no {@code {}} boundaries are found the cleaned string is returned
 * as-is so that the caller's Jackson parse produces a meaningful error.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LlmOutputParser {

    private final ObjectMapper mapper;

    /**
     * Strips Markdown fences and extracts the first top-level JSON object
     * from {@code raw}, then deserialises it into {@code type}.
     *
     * @param raw   the raw string returned by the LLM
     * @param type  the target Java class
     * @param <T>   the target type
     * @return the deserialised object
     * @throws com.fasterxml.jackson.core.JsonProcessingException if the
     *         extracted text is not valid JSON for {@code type}
     */
    public <T> T parse(String raw, Class<T> type)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        return mapper.readValue(extractJson(raw), type);
    }

    /**
     * Strips Markdown fences and extracts the first top-level JSON object
     * from {@code raw}, returning it as a plain {@code String}.
     * Does not perform Jackson deserialisation.
     *
     * @param raw the raw LLM output
     * @return cleaned JSON string (may still be invalid JSON if the model
     *         did not produce any JSON object at all)
     */
    public String extractJson(String raw) {
        // 1. Trim surrounding whitespace.
        String cleaned = raw.trim();

        // 2. Strip ```json ... ``` or ``` ... ``` fences.
        cleaned = cleaned.replaceAll("(?s)^```json\\s*", "").replaceAll("(?s)^```\\s*", "");
        cleaned = cleaned.replaceAll("```$", "").trim();

        // 3. Locate the outermost { } boundaries.
        int start = cleaned.indexOf('{');
        int end   = cleaned.lastIndexOf('}') + 1;
        if (start >= 0 && end > start) {
            cleaned = cleaned.substring(start, end);
        }

        return cleaned.trim();
    }
}
