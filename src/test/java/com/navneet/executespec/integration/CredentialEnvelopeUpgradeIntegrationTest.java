package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.crypto.CryptoProperties;
import com.navneet.executespec.crypto.KeyMigrationRunner;
import com.navneet.executespec.crypto.SimpleKeyEncryptionService;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Production-shaped V278 -> current credential-envelope upgrade and startup backfill rehearsal. */
@Tag("integration")
@Testcontainers
class CredentialEnvelopeUpgradeIntegrationTest {

  private static final String LOCAL_KEY = Base64.getEncoder().encodeToString(new byte[32]);

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("executespec_test")
          .withUsername("test")
          .withPassword("test");

  @Test
  void upgradeWidensScmSnapshotsAndStrictBackfillIsEncryptedAndIdempotent() {
    String schema = "credential_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    String baseUrl = POSTGRES.getJdbcUrl();
    String url =
        baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    try {
      flyway(dataSource, schema, MigrationVersion.fromVersion("278")).migrate();

      String longBitbucketSecret = "bb-" + "x".repeat(477); // 480 chars: valid pre-V280.
      seedLegacyCredentialFixture(jdbc, longBitbucketSecret);

      Flyway upgrade = flyway(dataSource, schema, null);
      int expectedForwardMigrations = upgrade.info().pending().length;
      assertThat(expectedForwardMigrations).isGreaterThanOrEqualTo(2);
      assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(expectedForwardMigrations);
      assertCredentialColumnsAreText(jdbc);

      CryptoProperties properties = new CryptoProperties();
      properties.setEnabled(true);
      properties.setLocalKey(LOCAL_KEY);
      properties.setRequireEncryption(true);
      properties.setKeyVersion("test-v1");
      SimpleKeyEncryptionService encryption = new SimpleKeyEncryptionService(properties);
      ReflectionTestUtils.invokeMethod(encryption, "init");

      KeyMigrationRunner runner = new KeyMigrationRunner(encryption, jdbc, properties);
      runner.migrateExistingKeys();

      Map<String, String> firstPass = readSeededCiphertexts(jdbc);
      assertThat(firstPass.values()).allMatch(value -> value.startsWith("enc:v1:test-v1:"));
      assertThat(firstPass.get("user_bitbucket").length()).isGreaterThan(512);
      assertThat(encryption.decrypt(firstPass.get("user_bitbucket")))
          .isEqualTo(longBitbucketSecret);
      assertThat(encryption.decrypt(firstPass.get("run_bitbucket"))).isEqualTo(longBitbucketSecret);
      assertThat(encryption.decrypt(firstPass.get("user_github"))).isEqualTo("ghp-user-legacy");
      assertThat(encryption.decrypt(firstPass.get("run_github"))).isEqualTo("ghp-run-legacy");
      assertThat(encryption.decrypt(firstPass.get("slack"))).isEqualTo("xoxb-legacy");

      // A restart must skip exact envelopes; re-encrypting would change AES-GCM IV/ciphertext.
      runner.migrateExistingKeys();
      assertThat(readSeededCiphertexts(jdbc)).isEqualTo(firstPass);
      assertThat(flyway(dataSource, schema, null).migrate().migrationsExecuted).isZero();
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private static Flyway flyway(
      DriverManagerDataSource dataSource, String schema, MigrationVersion target) {
    var config =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true);
    if (target != null) config.target(target);
    return config.load();
  }

  private static void seedLegacyCredentialFixture(JdbcTemplate jdbc, String bitbucketSecret) {
    jdbc.update(
        """
        INSERT INTO org
          (id, name, domain_patterns, created_by_user_id, openai_api_key, jira_api_token)
        VALUES
          ('org-credential-upgrade', 'Credential Upgrade', 'example.com', 'user-upgrade',
           'sk-org-legacy', 'jira-org-legacy')
        """);
    jdbc.update(
        """
        INSERT INTO app_user
          (id, email, first_name, last_name, password_hash, status, org_id,
           openai_api_key, github_pat, bitbucket_app_password)
        VALUES
          ('user-upgrade', 'credential-upgrade@example.com', 'Credential', 'Upgrade', 'hash',
           'ACTIVE', 'org-credential-upgrade', 'sk-user-legacy', 'ghp-user-legacy', ?)
        """,
        bitbucketSecret);
    jdbc.update(
        """
        INSERT INTO pipeline_run
          (run_id, status, github_pat, bitbucket_app_password)
        VALUES ('run-credential-upgrade', 'QUEUED', 'ghp-run-legacy', ?)
        """,
        bitbucketSecret);
    jdbc.update(
        """
        INSERT INTO org_slack_integration (org_id, team_id, bot_token)
        VALUES ('org-credential-upgrade', 'team-upgrade', 'xoxb-legacy')
        """);
  }

  private static void assertCredentialColumnsAreText(JdbcTemplate jdbc) {
    for (String table : java.util.List.of("app_user", "pipeline_run")) {
      assertThat(
              jdbc.queryForObject(
                  """
                  SELECT data_type FROM information_schema.columns
                  WHERE table_schema = current_schema()
                    AND table_name = ?
                    AND column_name = 'bitbucket_app_password'
                  """,
                  String.class,
                  table))
          .isEqualTo("text");
    }
  }

  private static Map<String, String> readSeededCiphertexts(JdbcTemplate jdbc) {
    Map<String, String> values = new LinkedHashMap<>();
    Map<String, Object> user =
        jdbc.queryForMap(
            "SELECT openai_api_key, github_pat, bitbucket_app_password FROM app_user"
                + " WHERE id='user-upgrade'");
    values.put("user_openai", (String) user.get("openai_api_key"));
    values.put("user_github", (String) user.get("github_pat"));
    values.put("user_bitbucket", (String) user.get("bitbucket_app_password"));
    Map<String, Object> org =
        jdbc.queryForMap(
            "SELECT openai_api_key, jira_api_token FROM org WHERE id='org-credential-upgrade'");
    values.put("org_openai", (String) org.get("openai_api_key"));
    values.put("org_jira", (String) org.get("jira_api_token"));
    Map<String, Object> run =
        jdbc.queryForMap(
            "SELECT github_pat, bitbucket_app_password FROM pipeline_run"
                + " WHERE run_id='run-credential-upgrade'");
    values.put("run_github", (String) run.get("github_pat"));
    values.put("run_bitbucket", (String) run.get("bitbucket_app_password"));
    values.put(
        "slack",
        jdbc.queryForObject(
            "SELECT bot_token FROM org_slack_integration WHERE team_id='team-upgrade'",
            String.class));
    return values;
  }
}
