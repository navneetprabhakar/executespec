package com.navneet.executespec.standards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.standards.model.StandardsParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * RR-596 — the project-exception write path must prove the target org standard exists, is unlocked,
 * and (for a downgrade) that the requested severity is strictly lower, instead of persisting a
 * no-op override and reporting success.
 */
@ExtendWith(MockitoExtension.class)
class EffectiveStandardsResolverExceptionWriteTest {

  @Mock private OrgStandardRepository orgStandardRepo;
  @Mock private ProjectStandardOverrideRepository overrideRepo;

  private EffectiveStandardsResolver resolver() {
    return new EffectiveStandardsResolver(orgStandardRepo, overrideRepo, new StandardsParser());
  }

  private static OrgStandard orgStandard(String severity, boolean locked) {
    String yaml =
        "- id: no-skip\n"
            + "  severity: "
            + severity
            + "\n"
            + "  description: no skip migrations\n"
            + "  enforcement:\n"
            + "    type: file-pattern\n"
            + "    forbidden_patterns: ['**/V*__SKIP*.sql']\n";
    return OrgStandard.builder()
        .orgId("org-1")
        .standardId("no-skip")
        .standardYaml(yaml)
        .orgLevelLocked(locked)
        .build();
  }

  @Test
  void unknownStandard_reportsNotFound() {
    when(orgStandardRepo.findByOrgIdAndStandardId("org-1", "ghost")).thenReturn(Optional.empty());
    assertThat(
            resolver()
                .validateProjectException(
                    "org-1", "ghost", ProjectStandardOverride.OverrideType.NOT_APPLICABLE, null)
                .result())
        .isEqualTo(EffectiveStandardsResolver.ExceptionWriteCheck.Result.STANDARD_NOT_FOUND);
  }

  @Test
  void lockedStandard_reportsLocked() {
    when(orgStandardRepo.findByOrgIdAndStandardId("org-1", "no-skip"))
        .thenReturn(Optional.of(orgStandard("BLOCKING", true)));
    assertThat(
            resolver()
                .validateProjectException(
                    "org-1", "no-skip", ProjectStandardOverride.OverrideType.NOT_APPLICABLE, null)
                .result())
        .isEqualTo(EffectiveStandardsResolver.ExceptionWriteCheck.Result.STANDARD_LOCKED);
  }

  @Test
  void severityUpgradeDisguisedAsDowngrade_reportsNotADowngrade() {
    when(orgStandardRepo.findByOrgIdAndStandardId("org-1", "no-skip"))
        .thenReturn(Optional.of(orgStandard("WARNING", false)));
    assertThat(
            resolver()
                .validateProjectException(
                    "org-1",
                    "no-skip",
                    ProjectStandardOverride.OverrideType.SEVERITY_DOWNGRADE,
                    "BLOCKING")
                .result())
        .isEqualTo(EffectiveStandardsResolver.ExceptionWriteCheck.Result.NOT_A_DOWNGRADE);
  }

  @Test
  void invalidSeverityToken_reportsInvalidSeverity() {
    when(orgStandardRepo.findByOrgIdAndStandardId("org-1", "no-skip"))
        .thenReturn(Optional.of(orgStandard("BLOCKING", false)));
    assertThat(
            resolver()
                .validateProjectException(
                    "org-1",
                    "no-skip",
                    ProjectStandardOverride.OverrideType.SEVERITY_DOWNGRADE,
                    "NOT_A_SEVERITY")
                .result())
        .isEqualTo(EffectiveStandardsResolver.ExceptionWriteCheck.Result.INVALID_SEVERITY);
  }

  @Test
  void strictDowngrade_ok() {
    when(orgStandardRepo.findByOrgIdAndStandardId("org-1", "no-skip"))
        .thenReturn(Optional.of(orgStandard("BLOCKING", false)));
    assertThat(
            resolver()
                .validateProjectException(
                    "org-1",
                    "no-skip",
                    ProjectStandardOverride.OverrideType.SEVERITY_DOWNGRADE,
                    "WARNING")
                .result())
        .isEqualTo(EffectiveStandardsResolver.ExceptionWriteCheck.Result.OK);
  }

  @Test
  void notApplicableOnUnlockedStandard_ok() {
    when(orgStandardRepo.findByOrgIdAndStandardId("org-1", "no-skip"))
        .thenReturn(Optional.of(orgStandard("BLOCKING", false)));
    assertThat(
            resolver()
                .validateProjectException(
                    "org-1", "no-skip", ProjectStandardOverride.OverrideType.NOT_APPLICABLE, null)
                .result())
        .isEqualTo(EffectiveStandardsResolver.ExceptionWriteCheck.Result.OK);
  }
}
