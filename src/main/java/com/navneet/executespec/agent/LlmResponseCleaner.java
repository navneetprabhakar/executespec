package com.navneet.executespec.agent;

/**
 * Utility for cleaning raw LLM response strings (markdown fence stripping, JSON extraction).
 */
public final class LlmResponseCleaner {
    private LlmResponseCleaner() {}

    /** Strips markdown code fences (```json ... ```) from an LLM response. */
    public static String stripMarkdownFences(String response) {
        if (response == null) return null;
        return response.trim()
                .replaceAll("(?s)^```(?:json)?\\s*", "")
                .replaceAll("(?s)```$", "")
                .trim();
    }

    /** Extracts the first JSON array from a string, or returns null if none found. */
    public static String extractJsonArray(String response) {
        if (response == null) return null;
        String cleaned = stripMarkdownFences(response);
        int s = cleaned.indexOf('[');
        int e = cleaned.lastIndexOf(']') + 1;
        if (s < 0 || e <= s) return null;
        return cleaned.substring(s, e);
    }

    /** Extracts the first JSON object from a string, or returns null if none found. */
    public static String extractJsonObject(String response) {
        if (response == null) return null;
        String cleaned = stripMarkdownFences(response);
        int s = cleaned.indexOf('{');
        int e = cleaned.lastIndexOf('}') + 1;
        if (s < 0 || e <= s) return null;
        return cleaned.substring(s, e);
    }
}
