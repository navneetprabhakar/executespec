package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class SarvamRemovalContractTest {

  @Test
  void productionProviderEnumAndModelMatrixContainNoSarvamRoute() throws Exception {
    assertThat(Arrays.stream(LlmProvider.values()).map(Enum::name)).doesNotContain("SARVAM");
    String application = Files.readString(Path.of("src/main/resources/application.yml"));
    assertThat(application.toLowerCase()).doesNotContain("sarvam");
  }

  @Test
  void productionSourcesContainNoRetiredSarvamToken() throws Exception {
    // Keep the historical token only in the forward migration and upgrade fixtures.  A literal in
    // production Java/config would make the retired provider discoverable again through a new
    // route, catalog, or fallback branch.
    try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
      files
          .filter(Files::isRegularFile)
          .filter(SarvamRemovalContractTest::isProductionText)
          .forEach(
              file -> {
                try {
                  assertThat(Files.readString(file).toLowerCase(Locale.ROOT))
                      .as("retired Sarvam token in %s", file)
                      .doesNotContain("sarvam");
                } catch (Exception e) {
                  throw new IllegalStateException("Unable to inspect " + file, e);
                }
              });
    }
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
        .contains("current_provider")
        .contains("current_model")
        .contains("continuation_decision_json")
        .contains("continuation_actions_json")
        .contains("update org");
  }

  private static boolean isProductionText(Path file) {
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.endsWith(".java")
        || name.endsWith(".yml")
        || name.endsWith(".yaml")
        || name.endsWith(".properties")
        || name.endsWith(".json")
        || name.endsWith(".xml");
  }
}
