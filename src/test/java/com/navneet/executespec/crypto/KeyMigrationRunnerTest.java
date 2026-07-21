package com.navneet.executespec.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
  private static final String ROTATED_KEY =
      Base64.getEncoder()
          .encodeToString(
              new byte[] {
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1
              });

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
    assertThat(KeyMigrationRunner.PIPELINE_RUN_SECRET_COLUMNS)
        .containsExactlyInAnyOrder("github_pat", "bitbucket_app_password");
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

    new KeyMigrationRunner(service, jdbc, properties(false)).migrateExistingKeys();

    // RR-123: rows are rewritten in one batchUpdate per page, not one update per row.
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Object[]>> batch = ArgumentCaptor.forClass(List.class);
    verify(jdbc).batchUpdate(contains("org_slack_integration"), batch.capture());
    Map<Object, String> byKey = new java.util.HashMap<>();
    for (Object[] args : batch.getValue()) {
      byKey.put(args[1], args[0].toString());
    }
    // id 3 (already a current envelope) is skipped; only id 1 + id 2 are batched.
    assertThat(byKey).hasSize(2).doesNotContainKey(3L);
    assertThat(byKey.get(1L)).startsWith("enc:v1:1:");
    assertThat(service.decrypt(byKey.get(1L))).isEqualTo("xoxb-plaintext-slack-token");
    assertThat(service.decrypt(byKey.get(2L))).isEqualTo("legacy-secret");
    verify(jdbc, never()).update(anyString(), any(), any());
  }

  @Test
  void retiredKeyEnvelopeIsRewrappedSoPreviousKeyCanBeRemoved() {
    SimpleKeyEncryptionService oldService = service(KEY, "old", Map.of());
    String oldEnvelope = oldService.encrypt("rotate-and-retire");
    SimpleKeyEncryptionService rotatedService = service(ROTATED_KEY, "current", Map.of("old", KEY));
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString())).thenReturn(List.of());
    when(jdbc.queryForList(contains("org_slack_integration")))
        .thenReturn(List.of(Map.of("id", 7L, "bot_token", oldEnvelope)));

    new KeyMigrationRunner(rotatedService, jdbc, properties(true)).migrateExistingKeys();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Object[]>> batch = ArgumentCaptor.forClass(List.class);
    verify(jdbc).batchUpdate(contains("org_slack_integration"), batch.capture());
    assertThat(batch.getValue()).hasSize(1);
    Object[] args = batch.getValue().get(0);
    assertThat(args[1]).isEqualTo(7L);
    String currentEnvelope = args[0].toString();
    assertThat(currentEnvelope).startsWith("enc:v1:current:");
    SimpleKeyEncryptionService afterRetirement = service(ROTATED_KEY, "current", Map.of());
    assertThat(afterRetirement.decrypt(currentEnvelope)).isEqualTo("rotate-and-retire");
    assertThatThrownBy(() -> afterRetirement.decrypt(oldEnvelope))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No key registered");
  }

  @Test
  void pipelineRunCredentialsUseRunIdAsTheMigrationKey() {
    SimpleKeyEncryptionService service = liveService();
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString())).thenReturn(List.of());
    when(jdbc.queryForList(contains("SELECT run_id, github_pat FROM pipeline_run")))
        .thenReturn(List.of(Map.of("run_id", "run-1", "github_pat", "ghp-legacy")));

    new KeyMigrationRunner(service, jdbc, properties(false)).migrateExistingKeys();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Object[]>> batch = ArgumentCaptor.forClass(List.class);
    verify(jdbc)
        .batchUpdate(
            contains("UPDATE pipeline_run SET github_pat = ? WHERE run_id = ?"), batch.capture());
    assertThat(batch.getValue()).hasSize(1);
    Object[] args = batch.getValue().get(0);
    assertThat(args[1]).isEqualTo("run-1");
    assertThat(service.decrypt(args[0].toString())).isEqualTo("ghp-legacy");
  }

  @Test
  void largeColumnIsPagedByKeysetAndBatched() {
    // RR-123: a full first page must trigger a second keyset page fetch, and each page must be
    // written with a single batchUpdate — never a per-row update — so boot stays bounded.
    SimpleKeyEncryptionService service = liveService();
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString())).thenReturn(List.of());

    List<Map<String, Object>> firstPage = new java.util.ArrayList<>();
    for (long i = 1; i <= 500; i++) {
      firstPage.add(Map.of("id", i, "bot_token", "xoxb-token-" + i));
    }
    when(jdbc.queryForList(contains("org_slack_integration"))).thenReturn(firstPage);
    when(jdbc.queryForList(contains("org_slack_integration"), any(Object.class)))
        .thenReturn(List.of(Map.of("id", 501L, "bot_token", "xoxb-token-501")));

    new KeyMigrationRunner(service, jdbc, properties(false)).migrateExistingKeys();

    // First page via single-arg query; second page via keyset (two-arg) query with cursor = 500.
    verify(jdbc).queryForList(contains("org_slack_integration"));
    verify(jdbc).queryForList(contains("org_slack_integration"), eq(500L));
    // One batchUpdate per page (500 rows, then 1 row); never a per-row update.
    verify(jdbc, org.mockito.Mockito.times(2))
        .batchUpdate(contains("org_slack_integration"), any(List.class));
    verify(jdbc, never()).update(anyString(), any(), any());
  }

  @Test
  void skipsEntirelyWhenEncryptionIsNotActive() {
    CryptoProperties props = new CryptoProperties();
    props.setEnabled(false);
    SimpleKeyEncryptionService disabled = new SimpleKeyEncryptionService(props);
    disabled.init();
    JdbcTemplate jdbc = mock(JdbcTemplate.class);

    new KeyMigrationRunner(disabled, jdbc, properties(false)).migrateExistingKeys();

    verify(jdbc, never()).queryForList(anyString());
  }

  @Test
  void requiredEncryptionFailsStartupWhenAnyCredentialColumnCannotBeMigrated() {
    SimpleKeyEncryptionService service = liveService();
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString()))
        .thenThrow(new IllegalStateException("database unavailable"));

    KeyMigrationRunner runner = new KeyMigrationRunner(service, jdbc, properties(true));

    assertThatThrownBy(runner::migrateExistingKeys)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app_user.openai_api_key")
        .hasMessageContaining("IllegalStateException")
        .hasMessageNotContaining("database unavailable")
        .hasNoCause();
  }

  @Test
  void optionalEncryptionKeepsDevelopmentBootCompatibleButSurfacesMigrationFailure() {
    SimpleKeyEncryptionService service = liveService();
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString())).thenThrow(new IllegalStateException("table unavailable"));

    new KeyMigrationRunner(service, jdbc, properties(false)).migrateExistingKeys();

    verify(jdbc, org.mockito.Mockito.times(20)).queryForList(anyString());
  }

  private static CryptoProperties properties(boolean required) {
    CryptoProperties properties = new CryptoProperties();
    properties.setRequireEncryption(required);
    return properties;
  }

  private static SimpleKeyEncryptionService service(
      String key, String version, Map<String, String> previous) {
    CryptoProperties properties = new CryptoProperties();
    properties.setEnabled(true);
    properties.setLocalKey(key);
    properties.setRequireEncryption(true);
    properties.setKeyVersion(version);
    properties.setPreviousKeys(previous);
    SimpleKeyEncryptionService service = new SimpleKeyEncryptionService(properties);
    service.init();
    return service;
  }
}
