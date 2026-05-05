package com.navneet.executespec.codereview;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.codereview.entity.CodeReviewIssue;
import com.navneet.executespec.codereview.entity.CodeReviewScan;

import org.junit.jupiter.api.Test;

/**
 * Pins the markdown rendering contract — the chat surface + the download endpoint hand the user the
 * output of {@link CodeReviewMarkdownRenderer#render} verbatim, so it must:
 *
 * <ul>
 *   <li>group issues by severity in canonical order (CRITICAL → MAJOR → MINOR → INFO)
 *   <li>show file paths as monospaced fenced section headers
 *   <li>include the suggested fix block when present
 *   <li>render the dropped-findings section when the agent flagged false positives
 *   <li>show a "clean scan" note when there are zero issues
 * </ul>
 */
class CodeReviewMarkdownRendererTest {

  private CodeReviewScan scan(int input, int output, double cost) {
    CodeReviewScan s = new CodeReviewScan();
    s.setId(UUID.randomUUID());
    s.setProjectId("p1");
    s.setRepoFullName("acme/api-server");
    s.setScannedSha("abcdef1234567890");
    s.setStatus("COMPLETE");
    s.setStartedAt(Instant.parse("2026-04-15T12:00:00Z"));
    s.setDurationMs(138_000L);
    s.setCostUsd(cost);
    s.setTotalInputTokens((long) input);
    s.setTotalOutputTokens((long) output);
    return s;
  }

  private CodeReviewIssue issue(
      String severity, String category, String file, int line, String msg, String fix) {
    CodeReviewIssue i = new CodeReviewIssue();
    i.setSeverity(severity);
    i.setCategory(category);
    i.setSource("LLM");
    i.setRuleId("LLM-NATIVE-test");
    i.setFilePath(file);
    i.setLineStart(line);
    i.setLineEnd(line);
    i.setMessage(msg);
    i.setSuggestedFix(fix);
    i.setStatus("OPEN");
    return i;
  }

  @Test
  void renders_issues_grouped_by_severity_in_canonical_order() {
    CodeReviewScan s = scan(12000, 1500, 0.84);
    CodeReviewAgent.AgentResult agent =
        new CodeReviewAgent.AgentResult(
            "Generally clean. One serious bug in payments code.",
            List.of(),
            List.of(),
            138_000L,
            "anthropic",
            "claude-sonnet-4-6");
    List<CodeReviewIssue> persisted =
        List.of(
            issue("MAJOR", "BUG", "src/A.java", 10, "off by one", null),
            issue("CRITICAL", "SECURITY", "src/B.java", 42, "auth bypass", "verify caller id"),
            issue("MINOR", "STYLE", "src/C.java", 5, "unused", null));
    String md = CodeReviewMarkdownRenderer.render(s, agent, persisted, 0);

    int idxCritical = md.indexOf("## CRITICAL");
    int idxMajor = md.indexOf("## MAJOR");
    int idxMinor = md.indexOf("## MINOR");
    assertTrue(idxCritical >= 0, "CRITICAL section missing");
    assertTrue(idxMajor > idxCritical, "MAJOR must come after CRITICAL");
    assertTrue(idxMinor > idxMajor, "MINOR must come after MAJOR");
    assertTrue(md.contains("`src/B.java`"));
    assertTrue(md.contains("auth bypass"));
    assertTrue(md.contains("verify caller id"), "suggested fix block must appear when present");
    assertTrue(md.contains("provider=anthropic"));
    assertTrue(md.contains("model=claude-sonnet-4-6"));
  }

  @Test
  void shows_clean_scan_note_when_zero_issues() {
    CodeReviewScan s = scan(1000, 200, 0.04);
    CodeReviewAgent.AgentResult agent =
        new CodeReviewAgent.AgentResult(
            "Pristine.", List.of(), List.of(), 4000L, "openai", "gpt-5.4");
    String md = CodeReviewMarkdownRenderer.render(s, agent, List.of(), 0);
    assertTrue(md.contains("_No issues found — clean scan._"));
    assertFalse(md.contains("## CRITICAL"));
  }

  @Test
  void includes_dropped_findings_section_when_present() {
    CodeReviewScan s = scan(2000, 500, 0.21);
    CodeReviewAgent.AgentResult agent =
        new CodeReviewAgent.AgentResult(
            "Two FPs dropped.",
            List.of(),
            List.of(
                new CodeReviewAgent.DroppedStatic(
                    "java.lang.security.audit.weak-prng",
                    "src/test/Foo.java",
                    12,
                    "Test fixture using SeededRandom — not actually weak crypto.")),
            5000L,
            "gemini",
            "gemini-3-flash-preview");
    String md = CodeReviewMarkdownRenderer.render(s, agent, List.of(), 1);
    assertTrue(md.contains("## Dropped static findings"));
    assertTrue(md.contains("`java.lang.security.audit.weak-prng`"));
    assertTrue(md.contains("Test fixture using SeededRandom"));
    assertTrue(md.contains("1 static findings dropped as false positives"));
  }
}
