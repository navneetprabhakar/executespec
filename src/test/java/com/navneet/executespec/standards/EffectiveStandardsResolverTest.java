package com.navneet.executespec.standards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.List;

import com.navneet.executespec.standards.model.Standard;
import com.navneet.executespec.standards.model.StandardsParser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EffectiveStandardsResolverTest {

  @Mock OrgStandardRepository orgStandardRepo;
  @Mock ProjectStandardOverrideRepository overrideRepo;

  EffectiveStandardsResolver resolver;
  String orgId = "org-1";
  String projectId = "proj-1";

  @BeforeEach
  void setUp() {
    resolver = new EffectiveStandardsResolver(orgStandardRepo, overrideRepo, new StandardsParser());
  }

  @Test
  void noStandards_returnsEmpty() {
    when(orgStandardRepo.findByOrgId(orgId)).thenReturn(List.of());
    when(overrideRepo.findByProjectId(projectId)).thenReturn(List.of());
    assertThat(resolver.resolve(orgId, projectId, null)).isEmpty();
  }

  @Test
  void orgStandardWithoutOverride_passesThroughAtOrgSeverity() {
    when(orgStandardRepo.findByOrgId(orgId))
        .thenReturn(List.of(orgStandard("std-a", "blocking", false)));
    when(overrideRepo.findByProjectId(projectId)).thenReturn(List.of());

    List<EffectiveStandardsResolver.Effective> effective = resolver.resolve(orgId, projectId, null);

    assertThat(effective).hasSize(1);
    EffectiveStandardsResolver.Effective e = effective.get(0);
    assertThat(e.standard().severity()).isEqualTo(Standard.Severity.BLOCKING);
    assertThat(e.source()).isEqualTo(EffectiveStandardsResolver.Source.ORG);
    assertThat(e.overridden()).isFalse();
    assertThat(e.originalSeverity()).isEqualTo(Standard.Severity.BLOCKING);
  }

  @Test
  void notApplicableOverride_excludesStandardEntirely() {
    when(orgStandardRepo.findByOrgId(orgId))
        .thenReturn(List.of(orgStandard("std-a", "blocking", false)));
    when(overrideRepo.findByProjectId(projectId))
        .thenReturn(
            List.of(
                ProjectStandardOverride.builder()
                    .projectId(projectId)
                    .standardId("std-a")
                    .overrideType("NOT_APPLICABLE")
                    .rationale("we don't ship Java in this project")
                    .build()));

    List<EffectiveStandardsResolver.Effective> effective = resolver.resolve(orgId, projectId, null);
    assertThat(effective).isEmpty();
  }

  @Test
  void severityDowngrade_applied_andOriginalSeverityPreserved() {
    when(orgStandardRepo.findByOrgId(orgId))
        .thenReturn(List.of(orgStandard("std-a", "blocking", false)));
    when(overrideRepo.findByProjectId(projectId))
        .thenReturn(
            List.of(
                ProjectStandardOverride.builder()
                    .projectId(projectId)
                    .standardId("std-a")
                    .overrideType("SEVERITY_DOWNGRADE")
                    .newSeverity("WARNING")
                    .rationale("downgrade for migration phase")
                    .build()));

    EffectiveStandardsResolver.Effective e = resolver.resolve(orgId, projectId, null).get(0);
    assertThat(e.standard().severity()).isEqualTo(Standard.Severity.WARNING);
    assertThat(e.overridden()).isTrue();
    assertThat(e.originalSeverity()).isEqualTo(Standard.Severity.BLOCKING);
  }

  @Test
  void severityUpgradeAttempt_rejected_keepsOriginalSeverity() {
    // Org severity = WARNING; project tries to UPGRADE to BLOCKING → rejected
    when(orgStandardRepo.findByOrgId(orgId))
        .thenReturn(List.of(orgStandard("std-a", "warning", false)));
    when(overrideRepo.findByProjectId(projectId))
        .thenReturn(
            List.of(
                ProjectStandardOverride.builder()
                    .projectId(projectId)
                    .standardId("std-a")
                    .overrideType("SEVERITY_DOWNGRADE")
                    .newSeverity("BLOCKING")
                    .rationale("we want this to block builds")
                    .build()));

    EffectiveStandardsResolver.Effective e = resolver.resolve(orgId, projectId, null).get(0);
    assertThat(e.standard().severity()).isEqualTo(Standard.Severity.WARNING);
    assertThat(e.overridden()).isFalse();
  }

  @Test
  void orgLevelLocked_overrideIgnored_evenIfOverrideRowExists() {
    when(orgStandardRepo.findByOrgId(orgId))
        .thenReturn(List.of(orgStandard("std-a", "blocking", true)));
    when(overrideRepo.findByProjectId(projectId))
        .thenReturn(
            List.of(
                ProjectStandardOverride.builder()
                    .projectId(projectId)
                    .standardId("std-a")
                    .overrideType("NOT_APPLICABLE")
                    .rationale("would skip this if allowed")
                    .build()));

    List<EffectiveStandardsResolver.Effective> effective = resolver.resolve(orgId, projectId, null);
    // Standard still present at original severity; orgLevelLocked flag set
    assertThat(effective).hasSize(1);
    assertThat(effective.get(0).orgLevelLocked()).isTrue();
    assertThat(effective.get(0).standard().severity()).isEqualTo(Standard.Severity.BLOCKING);
  }

  @Test
  void projectLevelStandard_replacesOrgEntryWithSameId_unlessLocked() {
    when(orgStandardRepo.findByOrgId(orgId))
        .thenReturn(List.of(orgStandard("std-a", "blocking", false)));
    when(overrideRepo.findByProjectId(projectId)).thenReturn(List.of());

    String projectYaml =
        """
        - id: std-a
          severity: warning
          description: project-level redefinition
          enforcement: { type: lint, tool: eslint }
        """;

    EffectiveStandardsResolver.Effective e = resolver.resolve(orgId, projectId, projectYaml).get(0);
    assertThat(e.source()).isEqualTo(EffectiveStandardsResolver.Source.PROJECT);
    assertThat(e.standard().severity()).isEqualTo(Standard.Severity.WARNING);
  }

  @Test
  void projectLevelStandard_doesNotReplaceLockedOrgStandard() {
    when(orgStandardRepo.findByOrgId(orgId))
        .thenReturn(List.of(orgStandard("std-a", "blocking", true)));
    when(overrideRepo.findByProjectId(projectId)).thenReturn(List.of());

    String projectYaml =
        """
        - id: std-a
          severity: warning
          description: project tries to redefine
          enforcement: { type: lint, tool: eslint }
        """;

    EffectiveStandardsResolver.Effective e = resolver.resolve(orgId, projectId, projectYaml).get(0);
    // Org-level locked entry preserved
    assertThat(e.source()).isEqualTo(EffectiveStandardsResolver.Source.ORG);
    assertThat(e.standard().severity()).isEqualTo(Standard.Severity.BLOCKING);
    assertThat(e.orgLevelLocked()).isTrue();
  }

  @Test
  void projectLevelOnlyStandard_isIncluded() {
    when(orgStandardRepo.findByOrgId(orgId)).thenReturn(List.of());
    when(overrideRepo.findByProjectId(projectId)).thenReturn(List.of());

    String projectYaml =
        """
        - id: project-only-rule
          severity: advisory
          description: only ours
          enforcement: { type: lint, tool: eslint }
        """;

    List<EffectiveStandardsResolver.Effective> effective =
        resolver.resolve(orgId, projectId, projectYaml);
    assertThat(effective).hasSize(1);
    assertThat(effective.get(0).source()).isEqualTo(EffectiveStandardsResolver.Source.PROJECT);
  }

  @Test
  void severityRanking_sanity() {
    assertThat(EffectiveStandardsResolver.severityRank(Standard.Severity.BLOCKING))
        .isGreaterThan(EffectiveStandardsResolver.severityRank(Standard.Severity.WARNING));
    assertThat(EffectiveStandardsResolver.severityRank(Standard.Severity.WARNING))
        .isGreaterThan(EffectiveStandardsResolver.severityRank(Standard.Severity.ADVISORY));
  }

  private OrgStandard orgStandard(String id, String severity, boolean locked) {
    String yaml =
        String.format(
            """
            - id: %s
              severity: %s
              description: test standard
              enforcement: { type: lint, tool: eslint }
            """,
            id, severity);
    return OrgStandard.builder()
        .orgId(orgId)
        .standardId(id)
        .standardYaml(yaml)
        .orgLevelLocked(locked)
        .build();
  }
}
