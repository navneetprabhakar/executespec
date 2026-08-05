package com.navneet.executespec.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.codeintel.ProjectArchitectureService;
import com.navneet.executespec.codeintel.repository.ProjectCodeMapRepository;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;

class FeatureAssessmentServiceRiskTest {

  private static final String SCHEMA_RISK =
      "Schema change — verify backwards compatibility before deploy";

  private final FeatureAssessmentService service =
      new FeatureAssessmentService(
          mock(ProjectRepoLinkRepository.class),
          mock(ProjectRepoRepository.class),
          mock(ProjectArchitectureService.class),
          mock(ProjectCodeMapRepository.class),
          mock(FeatureFlagService.class),
          new ObjectMapper(),
          mock(LlmRouter.class),
          mock(PipelineRunRepository.class),
          mock(ModelResolver.class));

  @Test
  void explicitSchemaNegationDoesNotCreateAFalseRisk() {
    assertThat(service.assess("run-1", null, "Add an endpoint; do not change the schema").risks())
        .doesNotContain(SCHEMA_RISK);
    assertThat(service.assess("run-1", null, "Implement this without a database migration").risks())
        .doesNotContain(SCHEMA_RISK);
  }

  @Test
  void boundedNegationDoesNotHideALaterPositiveSchemaChange() {
    var risks =
        service
            .assess(
                "run-1",
                null,
                "Do not change schema in the API layer, but add a database migration for users")
            .risks();

    assertThat(risks).contains(SCHEMA_RISK);
  }

  @Test
  void explicitSchemaMutationStillCreatesRiskWhileSchemaValidationDoesNot() {
    assertThat(service.assess("run-1", null, "Alter table users to add status").risks())
        .contains(SCHEMA_RISK);
    assertThat(service.assess("run-1", null, "Add JSON schema validation").risks())
        .doesNotContain(SCHEMA_RISK);
  }
}
