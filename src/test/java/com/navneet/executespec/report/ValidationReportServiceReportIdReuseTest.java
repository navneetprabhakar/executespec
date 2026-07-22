package com.navneet.executespec.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.report.entity.ValidationReport;

import org.junit.jupiter.api.Test;

/**
 * RR-295 — report replacement must reuse the existing primary key (atomic PK upsert) instead of a
 * destructive delete-then-save, so a failed regeneration cannot erase the only durable report.
 */
class ValidationReportServiceReportIdReuseTest {

  private final ValidationReportRepository repository = mock(ValidationReportRepository.class);

  private final ValidationReportService svc =
      new ValidationReportService(
          repository, null, null, null, null, null, mock(FeatureFlagService.class));

  @Test
  void resolveReportId_reusesExistingReportPrimaryKey() {
    ValidationReport existing = ValidationReport.builder().id("existing-id").runId("run-1").build();
    when(repository.findByRunId("run-1")).thenReturn(Optional.of(existing));

    assertThat(svc.resolveReportId("run-1")).isEqualTo("existing-id");
    // No destructive pre-delete of the prior report.
    verify(repository, never()).delete(existing);
  }

  @Test
  void resolveReportId_mintsFreshIdWhenNoPriorReport() {
    when(repository.findByRunId("run-2")).thenReturn(Optional.empty());

    String id = svc.resolveReportId("run-2");
    assertThat(id).isNotBlank();
    assertThat(id).isNotEqualTo("run-2");
  }
}
