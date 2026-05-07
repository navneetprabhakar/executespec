package com.navneet.executespec.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;

/**
 * Pins the validation-report block injected into ChatService's system prompt by Fix F (run a076c876
 * diagnosis 2026-05-03).
 *
 * <p>Diagnosis: pre-Fix-F the system prompt had no validation context at all — so when the user
 * asked the run-chat "why is architecture 0?", the LLM had no anchor and either hallucinated or
 * apologised. The new {@link ChatService#renderValidationReport} block surfaces the verifiedScore /
 * accuracyScore / reviewer verdict / per-dimension scores / capped raw issue JSON so the LLM can
 * quote them verbatim.
 */
class ChatServiceValidationReportTest {

  private final PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
  private final LlmRouter router = mock(LlmRouter.class);
  private final ModelResolver modelResolver = mock(ModelResolver.class);
  private final AgentProperties agentProps = mock(AgentProperties.class);
  private final ValidationReportRepository reportRepo = mock(ValidationReportRepository.class);

  private ChatService newService() {
    return new ChatService(runRepo, router, modelResolver, agentProps, reportRepo);
  }

  @Test
  void render_returns_empty_when_no_report_exists() {
    lenient().when(reportRepo.findByRunId(anyString())).thenReturn(Optional.empty());
    String s = newService().renderValidationReport("never-reported");
    assertThat(s).isEmpty();
  }

  @Test
  void render_returns_empty_when_repo_throws() {
    lenient().when(reportRepo.findByRunId(anyString())).thenThrow(new RuntimeException("DB blip"));
    String s = newService().renderValidationReport("any");
    assertThat(s).isEmpty();
  }

  @Test
  void render_includes_verified_and_accuracy_scores() {
    ValidationReport rpt = new ValidationReport();
    rpt.setVerifiedScore(72);
    rpt.setAccuracyScore(88);
    rpt.setAccuracyGrade("B");
    rpt.setReviewerVerdict("FIXED_ISSUES");
    rpt.setFinalBuildPassed(true);
    when(reportRepo.findByRunId("run-1")).thenReturn(Optional.of(rpt));

    String s = newService().renderValidationReport("run-1");

    assertThat(s)
        .contains("## Validation report")
        .contains("verifiedScore: 72/100")
        .contains("accuracyScore: 88/100")
        .contains("(B)")
        .contains("reviewerVerdict: FIXED_ISSUES")
        .contains("finalBuildPassed: true");
  }

  @Test
  void render_skips_null_dimension_scores_but_keeps_set_ones() {
    ValidationReport rpt = new ValidationReport();
    rpt.setFinalBuildPassed(true);
    rpt.setArchitectureScore(0); // The a076c876 case — explicitly set to 0
    rpt.setSecurityScore(55);
    // codeQuality / performance / designCompliance / testCoverage left null
    when(reportRepo.findByRunId("a076c876")).thenReturn(Optional.of(rpt));

    String s = newService().renderValidationReport("a076c876");

    assertThat(s).contains("architecture: 0/100").contains("security: 55/100");
    // Null dimensions must NOT appear as "null/100" lines.
    assertThat(s).doesNotContain("codeQuality").doesNotContain("performance");
  }

  @Test
  void render_includes_capped_issues_json() {
    ValidationReport rpt = new ValidationReport();
    rpt.setFinalBuildPassed(true);
    rpt.setIssuesJson(
        "[{\"description\":\"Spec requirement not implemented: pplai-frontend-mobile repository"
            + " entirely absent\",\"severity\":\"WOULD_CAUSE_BUG\",\"stage\":\"REVIEWER_AGENT\"}]");
    when(reportRepo.findByRunId("a076c876")).thenReturn(Optional.of(rpt));

    String s = newService().renderValidationReport("a076c876");

    assertThat(s)
        .contains("### Issues caught")
        .contains("pplai-frontend-mobile repository entirely absent")
        .contains("WOULD_CAUSE_BUG");
  }

  @Test
  void render_truncates_huge_issues_json() {
    ValidationReport rpt = new ValidationReport();
    rpt.setFinalBuildPassed(true);
    StringBuilder huge = new StringBuilder("[");
    for (int i = 0; i < 200; i++) {
      huge.append("{\"description\":\"issue ").append(i).append("\"},");
    }
    huge.setLength(huge.length() - 1);
    huge.append("]");
    rpt.setIssuesJson(huge.toString());
    when(reportRepo.findByRunId("verbose")).thenReturn(Optional.of(rpt));

    String s = newService().renderValidationReport("verbose");

    assertThat(s).contains("…(truncated)");
    assertThat(s.length()).isLessThan(8_000); // bounded to keep the prompt small
  }

  @Test
  void render_skips_issues_block_when_empty_array() {
    ValidationReport rpt = new ValidationReport();
    rpt.setFinalBuildPassed(true);
    rpt.setIssuesJson("[]");
    when(reportRepo.findByRunId("clean")).thenReturn(Optional.of(rpt));

    String s = newService().renderValidationReport("clean");

    assertThat(s).doesNotContain("### Issues caught");
  }

  @Test
  void render_includes_test_count_when_present() {
    ValidationReport rpt = new ValidationReport();
    rpt.setFinalBuildPassed(true);
    rpt.setTestsPassed(42);
    rpt.setTestsTotal(45);
    when(reportRepo.findByRunId("with-tests")).thenReturn(Optional.of(rpt));

    String s = newService().renderValidationReport("with-tests");
    assertThat(s).contains("tests: 42/45");
  }

  /** Simple Jackson presence check — ObjectMapper must be on the classpath. */
  @Test
  void object_mapper_classpath_sanity() {
    assertThat(new ObjectMapper()).isNotNull();
  }
}
