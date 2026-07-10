package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class SarvamRemovalContractTest {

  @Test
  void productionProviderEnumAndModelMatrixContainNoSarvamRoute() throws Exception {
    assertThat(Arrays.stream(LlmProvider.values()).map(Enum::name)).doesNotContain("SARVAM");
    String application = Files.readString(Path.of("src/main/resources/application.yml"));
    assertThat(application.toLowerCase()).doesNotContain("sarvam");
  }

  @Test
  void forwardMigrationClearsEveryPersistedDispatchPreference() throws Exception {
    String migration =
        Files.readString(
                Path.of("src/main/resources/db/migration/V256__remove_sarvam_provider_state.sql"))
            .toLowerCase();
    assertThat(migration)
        .contains("update app_user")
        .contains("update project")
        .contains("update pipeline_run")
        .contains("update execution_run")
        .contains("update agent_session")
        .contains("update agent_task")
        .contains("update org");
  }
}
