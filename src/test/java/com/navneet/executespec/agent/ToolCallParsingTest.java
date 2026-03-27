package com.navneet.executespec.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link WorkerAgent#escapeControlCharsInJsonStrings(String)}.
 * Verifies that raw control characters inside JSON strings are properly escaped
 * so that Jackson can parse the result.
 */
class ToolCallParsingTest {

    @Test
    void escapeControlChars_handlesNewlinesInStrings() {
        // Simulates: {"name": "writeFile", "args": {"content": "line1\nline2"}}
        String raw = "{\"name\": \"writeFile\", \"args\": {\"content\": \"line1\nline2\"}}";
        String escaped = WorkerAgent.escapeControlCharsInJsonStrings(raw);
        assertTrue(escaped.contains("line1\\nline2"), "Newline should be escaped to \\n");
        assertFalse(escaped.contains("\n"), "Raw newline should not remain");
    }

    @Test
    void escapeControlChars_preservesAlreadyEscaped() {
        String raw = "{\"content\": \"line1\\nline2\"}";
        String escaped = WorkerAgent.escapeControlCharsInJsonStrings(raw);
        assertEquals(raw, escaped, "Already-escaped newlines should be preserved");
    }

    @Test
    void escapeControlChars_handlesTabs() {
        String raw = "{\"content\": \"col1\tcol2\"}";
        String escaped = WorkerAgent.escapeControlCharsInJsonStrings(raw);
        assertTrue(escaped.contains("col1\\tcol2"));
    }

    @Test
    void escapeControlChars_leavesStructuralWhitespaceAlone() {
        String raw = "{\n  \"name\": \"test\"\n}";
        String escaped = WorkerAgent.escapeControlCharsInJsonStrings(raw);
        // Newlines OUTSIDE strings should be preserved as-is
        assertTrue(escaped.contains("{\n  \"name\""));
    }
}
