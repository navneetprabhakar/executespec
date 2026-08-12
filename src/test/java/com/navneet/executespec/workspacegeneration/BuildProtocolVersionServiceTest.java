package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.state.RunMode;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;

class BuildProtocolVersionServiceTest {

  private final PipelineRunRepository runs = mock(PipelineRunRepository.class);
  private final FeatureFlagService flags = mock(FeatureFlagService.class);
  private final WorkspaceGenerationSchemaAttestor schemaAttestor =
      mock(WorkspaceGenerationSchemaAttestor.class);
  private final BuildProtocolVersionService service =
      new BuildProtocolVersionService(runs, flags, schemaAttestor);

  @Test
  void submissionReadsRolloutFlagOnceAndReturnsAnExplicitVersion() {
    when(flags.isEnabled(BuildProtocolVersionService.SHARED_KERNEL_ADMISSION_FLAG))
        .thenReturn(true);
    when(schemaAttestor.requireReady()).thenReturn(null);

    assertThat(service.selectForSubmission(RunMode.BUILD)).isEqualTo("SHARED_KERNEL_V2");
  }

  @Test
  void sharedProtocolAdmissionFailsClosedWhenDeployedSchemaCannotBeAttested() {
    when(flags.isEnabled(BuildProtocolVersionService.SHARED_KERNEL_ADMISSION_FLAG))
        .thenReturn(true);
    when(schemaAttestor.requireReady())
        .thenThrow(new IllegalStateException("BUILD_PROTOCOL_SCHEMA_ATTESTATION_FAILED"));

    assertThatThrownBy(() -> service.selectForSubmission(RunMode.BUILD))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("BUILD_PROTOCOL_SCHEMA_ATTESTATION_FAILED");
  }

  @Test
  void patchHasNoBuildProtocolAndDoesNotReadTheBuildFlag() {
    assertThat(service.selectForSubmission(RunMode.PATCH)).isNull();

    verifyNoInteractions(flags);
  }

  @Test
  void executionReadsPersistedVersionWithoutConsultingTheFlag() {
    when(runs.findById("run-v2"))
        .thenReturn(
            Optional.of(
                PipelineRun.builder()
                    .runId("run-v2")
                    .buildProtocolVersion("SHARED_KERNEL_V2")
                    .build()));

    assertThat(service.requireExecutable("run-v2"))
        .isEqualTo(BuildProtocolVersion.SHARED_KERNEL_V2);
    verifyNoInteractions(flags);
  }

  @Test
  void historicalUnknownRunFailsClosedInsteadOfReadingTheCurrentFlag() {
    when(runs.findById("run-unknown"))
        .thenReturn(Optional.of(PipelineRun.builder().runId("run-unknown").build()));

    assertThatThrownBy(() -> service.requireExecutable("run-unknown"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("BUILD_PROTOCOL_VERSION_UNPINNED");
    verifyNoInteractions(flags);
  }
}
