package com.navneet.executespec.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/** 2026-07-17 credential gate — the startup backfill covers ALL secret columns and re-envelopes. */
class KeyMigrationRunnerTest {

  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  private static SimpleKeyEncryptionService liveService() {
    CryptoProperties props = new CryptoProperties();
    props.setEnabled(true);
    props.setLocalKey(KEY);
    SimpleKeyEncryptionService service = new SimpleKeyEncryptionService(props);
    service.init();
    return service;
  }

  @Test
  void columnCoverageIncludesEveryPersistedSecret() {
    assertThat(KeyMigrationRunner.APP_USER_SECRET_COLUMNS)
        .contains(
            "github_pat",
            "bitbucket_app_password",
            "deepseek_api_key",
            "fireworks_api_key",
            "openai_api_key",
            "anthropic_api_key",
            "gemini_api_key",
            "jira_api_token",
            "confluence_api_token");
    assertThat(KeyMigrationRunner.ORG_SECRET_COLUMNS)
        .contains(
            "open_router_api_key",
            "deepseek_api_key",
            "fireworks_api_key",
            "openai_api_key",
            "anthropic_api_key",
            "gemini_api_key",
            "jira_api_token",
            "confluence_api_token");
    // token_hash is one-way SHA-256 by design and must NOT be enveloped.
    assertThat(KeyMigrationRunner.APP_USER_SECRET_COLUMNS).doesNotContain("token_hash");
  }

  @Test
  void plaintextAndLegacyRowsAreEnvelopedWhileEnvelopedRowsAreSkipped() {
    SimpleKeyEncryptionService service = liveService();
    JdbcTemplate jdbc = mock(JdbcTemplate.class);

    String legacy = service.encrypt("legacy-secret").substring("enc:v1:1:".length());
    String alreadyEnveloped = service.encrypt("done-already");
    when(jdbc.queryForList(anyString())).thenReturn(List.of());
    when(jdbc.queryForList(contains("org_slack_integration")))
        .thenReturn(
            List.of(
                Map.of("id", 1L, "bot_token", "xoxb-plaintext-slack-token"),
                Map.of("id", 2L, "bot_token", legacy),
                Map.of("id", 3L, "bot_token", alreadyEnveloped)));

    new KeyMigrationRunner(service, jdbc).migrateExistingKeys();

    ArgumentCaptor<Object> written = ArgumentCaptor.forClass(Object.class);
    verify(jdbc).update(contains("org_slack_integration"), written.capture(), eq(1L));
    assertThat(written.getValue().toString()).startsWith("enc:v1:1:");
    assertThat(service.decrypt(written.getValue().toString()))
        .isEqualTo("xoxb-plaintext-slack-token");

    ArgumentCaptor<Object> rewrapped = ArgumentCaptor.forClass(Object.class);
    verify(jdbc).update(contains("org_slack_integration"), rewrapped.capture(), eq(2L));
    assertThat(service.decrypt(rewrapped.getValue().toString())).isEqualTo("legacy-secret");

    verify(jdbc, never()).update(anyString(), any(), eq(3L));
  }

  @Test
  void skipsEntirelyWhenEncryptionIsNotActive() {
    CryptoProperties props = new CryptoProperties();
    props.setEnabled(false);
    SimpleKeyEncryptionService disabled = new SimpleKeyEncryptionService(props);
    disabled.init();
    JdbcTemplate jdbc = mock(JdbcTemplate.class);

    new KeyMigrationRunner(disabled, jdbc).migrateExistingKeys();

    verify(jdbc, never()).queryForList(anyString());
  }
}
