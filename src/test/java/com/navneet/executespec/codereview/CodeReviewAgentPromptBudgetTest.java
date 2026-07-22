package com.navneet.executespec.codereview;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * RR-935 — the static-finding section of the code-review prompt was unbounded: every finding was
 * appended with its full message and full snippet. A noisy/large repository could blow the provider
 * context window (failing the paid single call) or run up uncontrolled input cost. The section is
 * now a bounded, deterministic budget: top-N by severity, per-field truncation, explicit omitted
 * count.
 */
class CodeReviewAgentPromptBudgetTest {

  private final CodeReviewAgent agent = new CodeReviewAgent(null, null, null, null);

  @Test
  void findingSectionIsCappedAndStatesTheOmittedCount() {
    List<CodeReviewAgent.StaticFinding> findings = new ArrayList<>();
    for (int i = 0; i < CodeReviewAgent.MAX_FINDINGS_IN_PROMPT + 50; i++) {
      findings.add(
          new CodeReviewAgent.StaticFinding(
              "rule-" + i, "MINOR", "BUG", "src/File" + i + ".java", 1, 2, "msg " + i, null));
    }

    String prompt = agent.composeUserPrompt(request(findings));

    assertThat(prompt)
        .contains(CodeReviewAgent.MAX_FINDINGS_IN_PROMPT + " of " + findings.size() + " shown");
    assertThat(prompt).contains("50 further lower-severity static finding(s) omitted");
  }

  @Test
  void higherSeverityFindingsAreKeptOverLowerOnes() {
    List<CodeReviewAgent.StaticFinding> findings = new ArrayList<>();
    // One CRITICAL buried after a wall of INFO — it must survive the cap.
    for (int i = 0; i < CodeReviewAgent.MAX_FINDINGS_IN_PROMPT + 10; i++) {
      findings.add(
          new CodeReviewAgent.StaticFinding(
              "info-" + i, "INFO", "STYLE", "src/File" + i + ".java", 1, 2, "noise", null));
    }
    findings.add(
        new CodeReviewAgent.StaticFinding(
            "CRIT-1", "CRITICAL", "SECURITY", "src/Auth.java", 9, 9, "critical bug", null));

    String prompt = agent.composeUserPrompt(request(findings));

    assertThat(prompt).contains("CRIT-1");
  }

  @Test
  void longMessageAndSnippetAreTruncatedPerField() {
    String hugeMessage = "m".repeat(CodeReviewAgent.MAX_FINDING_MESSAGE_CHARS + 500);
    String hugeSnippet = "s".repeat(CodeReviewAgent.MAX_FINDING_SNIPPET_CHARS + 500);
    List<CodeReviewAgent.StaticFinding> findings =
        List.of(
            new CodeReviewAgent.StaticFinding(
                "rule-1", "MAJOR", "BUG", "src/Big.java", 1, 1, hugeMessage, hugeSnippet));

    String prompt = agent.composeUserPrompt(request(findings));

    assertThat(prompt).contains("(truncated)");
    assertThat(prompt).doesNotContain(hugeMessage);
    assertThat(prompt).doesNotContain(hugeSnippet);
  }

  private static CodeReviewAgent.AgentRequest request(
      List<CodeReviewAgent.StaticFinding> findings) {
    return CodeReviewAgent.AgentRequest.builder()
        .scanId("scan-1")
        .projectId("project-1")
        .repoFullName("org/service")
        .scannedSha("abc123")
        .languages(List.of("java"))
        .fileCount(10)
        .totalLoc(100)
        .scanCoverage(CodeReviewAgent.ScanCoverage.COMPLETE)
        .staticFindings(findings)
        .fileSamples(List.of())
        .build();
  }
}
