package com.navneet.executespec.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.cost.RunEffectiveRouteService;
import com.navneet.executespec.cost.RunStepUsage;
import com.navneet.executespec.cost.RunStepUsageRepository;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.report.model.ValidationReportDTO;

import org.junit.jupiter.api.Test;

/**
 * The report DTO must not claim one {@code modelUsed} for a run whose physical attribution is
 * MIXED. Additive {@code modelsUsed} + {@code routeAttribution} carry the ledger truth; the legacy
 * field carries the literal {@code "MIXED"} instead of an arbitrary single model.
 */
class ValidationReportDtoEffectiveRouteTest {

  private static final String RUN_ID = "run-report-route-1";

  private final ValidationReportRepository repository = mock(ValidationReportRepository.class);
  private final RunStepUsageRepository usageRepository = mock(RunStepUsageRepository.class);

  private ValidationReportService service() {
    return new ValidationReportService(
        repository,
        null,
        new ObjectMapper(),
        null,
        null,
        null,
        null,
        null,
        new RunEffectiveRouteService(usageRepository));
  }

  private static ValidationReport report() {
    return ValidationReport.builder()
        .id("vr-1")
        .runId(RUN_ID)
        .status("COMPLETE")
        .filesGenerated(3)
        .pipelineResult(PipelineResult.PASSED)
        .issuesJson("[]")
        .modelUsed("claude-sonnet-5")
        .build();
  }

  private static RunStepUsage success(String provider, String model) {
    return RunStepUsage.builder()
        .id(provider + ":" + model)
        .runId(RUN_ID)
        .provider(provider)
        .model(model)
        .attemptStatus("SUCCESS")
        .build();
  }

  @Test
  void mixedRouteRunDoesNotClaimASingleModelUsed() {
    when(repository.findByRunId(RUN_ID)).thenReturn(Optional.of(report()));
    when(usageRepository.findByRunIdOrderByCreatedAtAsc(RUN_ID))
        .thenReturn(
            List.of(
                success("deepseek", "deepseek-v4-pro-native"), success("openrouter", "glm-5.2")));

    ValidationReportDTO dto = service().getByRunId(RUN_ID).orElseThrow();

    assertThat(dto.modelUsed()).isEqualTo("MIXED");
    assertThat(dto.routeAttribution()).isEqualTo("MIXED");
    assertThat(dto.modelsUsed()).containsExactly("deepseek-v4-pro-native", "glm-5.2");
  }

  @Test
  void singleRouteRunKeepsTheLegacyModelUsedAndReportsLedgerAttribution() {
    when(repository.findByRunId(RUN_ID)).thenReturn(Optional.of(report()));
    when(usageRepository.findByRunIdOrderByCreatedAtAsc(RUN_ID))
        .thenReturn(List.of(success("anthropic", "claude-sonnet-5")));

    ValidationReportDTO dto = service().getByRunId(RUN_ID).orElseThrow();

    assertThat(dto.modelUsed()).isEqualTo("claude-sonnet-5");
    assertThat(dto.routeAttribution()).isEqualTo("LEDGER");
    assertThat(dto.modelsUsed()).containsExactly("claude-sonnet-5");
  }

  @Test
  void runWithNoSuccessfulAttemptsIsHonestlyUnattributedWithAnEmptyModelList() {
    when(repository.findByRunId(RUN_ID)).thenReturn(Optional.of(report()));
    when(usageRepository.findByRunIdOrderByCreatedAtAsc(RUN_ID)).thenReturn(List.of());

    ValidationReportDTO dto = service().getByRunId(RUN_ID).orElseThrow();

    assertThat(dto.modelUsed()).isEqualTo("claude-sonnet-5");
    assertThat(dto.routeAttribution()).isEqualTo("UNATTRIBUTED");
    assertThat(dto.modelsUsed()).isEmpty();
  }
}
