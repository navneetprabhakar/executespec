package com.navneet.executespec.standards.enforcement.llmreview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.standards.EffectiveStandardsResolver;
import com.navneet.executespec.standards.model.Standard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmReviewEnforcerTest {

    private final LlmReviewEnforcer enforcer = new LlmReviewEnforcer(new ObjectMapper());

    @Test
    void composePromptBlock_emptyWhenNoLlmReviewStandards() {
        EffectiveStandardsResolver.Effective lint = effectiveLint();
        assertThat(enforcer.composePromptBlock(List.of(lint))).isEmpty();
    }

    @Test
    void composePromptBlock_includesEachLlmReviewStandard_withSeverityAndPrompt() {
        EffectiveStandardsResolver.Effective lr = effectiveLlmReview(
                "no-pii-in-logs",
                Standard.Severity.BLOCKING,
                "Reject log statements with PII",
                "Examine all logging statements; flag any with email/phone/ssn.");
        String block = enforcer.composePromptBlock(List.of(lr, effectiveLint()));
        assertThat(block).contains("=== LLM_REVIEW_STANDARDS ===");
        assertThat(block).contains("standard: no-pii-in-logs (severity=BLOCKING)");
        assertThat(block).contains("Examine all logging statements");
        // Lint standard not included
        assertThat(block).doesNotContain("eslint");
    }

    @Test
    void parseReviewResult_extractsOnlyViolations() {
        String reviewerOutput = """
                Some preamble.
                {
                  "standardsReviewed": [
                    {"standardId": "no-pii-in-logs", "verdict": "VIOLATION", "details": "Found email in log line 42"},
                    {"standardId": "use-structured-logging", "verdict": "PASS", "details": "All logs use SLF4J"},
                    {"standardId": "no-todo-comments", "verdict": "VIOLATION", "details": "TODO at Foo.java:12"}
                  ]
                }
                """;
        List<LlmReviewEnforcer.LlmReviewViolation> v = enforcer.parseReviewResult(reviewerOutput);
        assertThat(v).hasSize(2);
        assertThat(v.get(0).standardId()).isEqualTo("no-pii-in-logs");
        assertThat(v.get(1).standardId()).isEqualTo("no-todo-comments");
    }

    @Test
    void parseReviewResult_handlesEmptyOrMalformedInput() {
        assertThat(enforcer.parseReviewResult(null)).isEmpty();
        assertThat(enforcer.parseReviewResult("")).isEmpty();
        assertThat(enforcer.parseReviewResult("not json at all")).isEmpty();
        assertThat(enforcer.parseReviewResult("{ broken")).isEmpty();
    }

    @Test
    void parseReviewResult_skipsEntriesMissingStandardId() {
        String out = """
                {"standardsReviewed": [
                  {"verdict": "VIOLATION", "details": "no id"},
                  {"standardId": "x", "verdict": "VIOLATION", "details": "ok"}
                ]}
                """;
        List<LlmReviewEnforcer.LlmReviewViolation> v = enforcer.parseReviewResult(out);
        assertThat(v).hasSize(1);
        assertThat(v.get(0).standardId()).isEqualTo("x");
    }

    @Test
    void parseReviewResult_caseInsensitiveOnVerdict() {
        String out = """
                {"standardsReviewed": [
                  {"standardId": "x", "verdict": "violation", "details": "lower"}
                ]}
                """;
        List<LlmReviewEnforcer.LlmReviewViolation> v = enforcer.parseReviewResult(out);
        assertThat(v).hasSize(1);
    }

    private EffectiveStandardsResolver.Effective effectiveLint() {
        Standard s = new Standard("lint-id", null, Standard.Severity.WARNING,
                "lint", new Standard.LintEnforcement("eslint", null),
                null, List.of(), false);
        return new EffectiveStandardsResolver.Effective(s, EffectiveStandardsResolver.Source.ORG, false,
                Standard.Severity.WARNING, false);
    }

    private EffectiveStandardsResolver.Effective effectiveLlmReview(
            String id, Standard.Severity sev, String desc, String prompt) {
        Standard s = new Standard(id, null, sev, desc,
                new Standard.LlmReviewEnforcement(prompt),
                null, List.of(), false);
        return new EffectiveStandardsResolver.Effective(s, EffectiveStandardsResolver.Source.ORG, false,
                sev, false);
    }
}
