package com.navneet.executespec.integration.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PublicationAdmissionServiceTest {

  private static final String RUN_ID = "run-publication";
  private static final String GENERATION = "a".repeat(64);

  private final ValidationReportRepository reports = mock(ValidationReportRepository.class);
  private final AgentTaskRepository tasks = mock(AgentTaskRepository.class);
  private final CorrectionWorkspaceTransaction generations =
      mock(CorrectionWorkspaceTransaction.class);
  private final AgentProperties properties = new AgentProperties();
  private final PublicationAdmissionService service =
      new PublicationAdmissionService(reports, tasks, generations, properties, new ObjectMapper());

  @BeforeEach
  void setUp() {
    properties.setWorkspace("/tmp/executespec-publication-test");
    when(reports.findByRunId(RUN_ID)).thenReturn(Optional.of(greenReport()));
    when(generations.captureGeneration(Path.of("/tmp/executespec-publication-test", RUN_ID)))
        .thenReturn(GENERATION);
    when(tasks.findByRunId(RUN_ID)).thenReturn(List.of());
  }

  @Test
  void admitsFreshBuildAndVerifiedTests() {
    assertThat(service.assertEligible(run()).workspaceGeneration()).isEqualTo(GENERATION);
  }

  @Test
  void staleWorkspaceEvidenceIsBlocked() {
    when(generations.captureGeneration(Path.of("/tmp/executespec-publication-test", RUN_ID)))
        .thenReturn("b".repeat(64));

    assertBlocked("PUBLICATION_EVIDENCE_STALE");
  }

  @Test
  void missingOrFailedTestsAreBlocked() {
    ValidationReport report = greenReport();
    report.setTestExecutionVerified(false);
    when(reports.findByRunId(RUN_ID)).thenReturn(Optional.of(report));

    assertBlocked("PUBLICATION_TESTS_UNPROVEN");

    report.setTestExecutionVerified(true);
    report.setTestsPassed(1);
    report.setTestsTotal(2);
    assertBlocked("PUBLICATION_TESTS_FAILED");
  }

  @Test
  void approvedRouteMustMatchRequestedAndPhysicalLedgerRoute() {
    AgentTask task =
        AgentTask.builder()
            .taskId(RUN_ID + "_T1")
            .runId(RUN_ID)
            .provider("openrouter")
            .model("deepseek-v4-pro")
            .effectiveProvider("openrouter")
            .effectiveModel("deepseek-v4-pro")
            .effectiveRouteAttribution("LEDGER")
            .continuationActionsJson(
                """
                [{"status":"APPROVED","provider":"openrouter","model":"x-ai/grok-4.20"}]
                """)
            .build();
    when(tasks.findByRunId(RUN_ID)).thenReturn(List.of(task));

    assertBlocked("PUBLICATION_APPROVED_ROUTE_MISMATCH");

    task.setProvider("openrouter");
    task.setModel("x-ai/grok-4.20");
    task.setEffectiveProvider("openrouter");
    task.setEffectiveModel("x-ai/grok-4.20");
    assertThat(service.assertEligible(run()).code()).isEqualTo("PUBLICATION_ELIGIBLE");
  }

  private void assertBlocked(String code) {
    assertThatThrownBy(() -> service.assertEligible(run()))
        .isInstanceOf(PublicationAdmissionService.PublicationBlockedException.class)
        .satisfies(
            failure ->
                assertThat(
                        ((PublicationAdmissionService.PublicationBlockedException) failure).code())
                    .isEqualTo(code));
  }

  private static PipelineRun run() {
    return PipelineRun.builder().runId(RUN_ID).status("COMPLETE").build();
  }

  private static ValidationReport greenReport() {
    return ValidationReport.builder()
        .id("report-1")
        .runId(RUN_ID)
        .status("COMPLETE")
        .workspaceGeneration(GENERATION)
        .finalBuildPassed(true)
        .testExecutionVerified(true)
        .testsPassed(2)
        .testsTotal(2)
        .build();
  }
}
