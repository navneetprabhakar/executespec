package com.navneet.executespec.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/** Static guardrails for the backward-compatible migration policy. */
class FlywayCompatibilityContractTest {

  private static final Path V256 =
      Path.of("src/main/resources/db/migration/V256__remove_sarvam_provider_state.sql");
  private static final Path POLICY = Path.of("docs/plans/executespec-2.0-flyway-compatibility.md");
  private static final Path APPLICATION = Path.of("src/main/resources/application.yml");

  @Test
  void currentCleanupIsForwardOnlyAndDoesNotRewriteSchemaHistory() throws IOException {
    String sql = Files.readString(V256).toLowerCase(Locale.ROOT);
    assertThat(sql)
        .doesNotContain("drop table")
        .doesNotContain("drop column")
        .doesNotContain("alter table")
        .doesNotContain("truncate table")
        .doesNotContain("delete from");
    assertThat(sql).contains("update app_user", "update pipeline_run", "update org");
  }

  @Test
  void policyForbidsRewritingAppliedMigrationsAndDefinesCleanInstallBaseline() throws IOException {
    String policy = Files.readString(POLICY);
    assertThat(policy)
        .contains("Applied `V*.sql` files are immutable")
        .contains("baseline migration")
        .contains("upgrade from a database that has applied V257");
  }

  @Test
  void defaultConfigurationDoesNotSuppressMissingAppliedMigrations() throws IOException {
    String application = Files.readString(APPLICATION);
    assertThat(application).doesNotContain("ignore-migration-patterns");
  }
}
