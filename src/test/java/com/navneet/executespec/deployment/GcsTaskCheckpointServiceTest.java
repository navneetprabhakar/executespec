package com.navneet.executespec.deployment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationArchiveService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.test.util.ReflectionTestUtils;

/** Unit coverage for the strict checkpoint receipt required by BUILD generation acceptance. */
@ExtendWith(MockitoExtension.class)
class GcsTaskCheckpointServiceTest {

  @Mock private GcsWorkspaceStore gcsStore;
  @Mock private WorkspaceGenerationArchiveService generationArchives;
  @Mock private Environment environment;

  @InjectMocks private GcsTaskCheckpointService service;

  private void enable(boolean enabled) {
    ReflectionTestUtils.setField(service, "enabled", enabled);
  }

  @Test
  void strictGenerationCheckpointFailsClosedInProductionWithoutGcs() {
    enable(true);
    when(gcsStore.isConfigured()).thenReturn(false);
    when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.checkpointGeneration("run-1", "T1", checkpoint()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("WORKSPACE_GENERATION_DURABLE_CHECKPOINT_UNAVAILABLE");
  }

  @Test
  void strictGenerationCheckpointIsExplicitlyHostBoundOutsideProduction() {
    enable(true);
    when(gcsStore.isConfigured()).thenReturn(false);
    when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(false);

    GcsTaskCheckpointService.CheckpointReceipt receipt =
        service.checkpointGeneration("run-1", "T1", checkpoint());

    org.assertj.core.api.Assertions.assertThat(receipt.kind()).isEqualTo("LOCAL_SAME_HOST");
    org.assertj.core.api.Assertions.assertThat(receipt.key()).startsWith("local-same-host://");
    org.assertj.core.api.Assertions.assertThat(receipt.generation())
        .isEqualTo(checkpoint().generation());
  }

  private static CorrectionWorkspaceTransaction.Checkpoint checkpoint() {
    Path root = Path.of("/tmp/workspace-generation-test").toAbsolutePath().normalize();
    return new CorrectionWorkspaceTransaction.Checkpoint(
        root,
        java.util.List.of(
            new CorrectionWorkspaceTransaction.RepositoryCheckpoint(
                root, "a".repeat(40), "b".repeat(40))));
  }
}
