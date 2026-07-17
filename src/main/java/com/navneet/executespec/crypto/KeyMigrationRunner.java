package com.navneet.executespec.crypto;

import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * One-time migration runner that encrypts existing plaintext API keys in the database.
 *
 * <p>Runs on {@link ApplicationReadyEvent} after all beans are initialized. Idempotent: only
 * encrypts rows where the plaintext column is NOT NULL. Since the JPA converter now handles
 * transparent encryption, this runner reads plaintext values and writes them back — the converter
 * encrypts on write.
 *
 * <p>Safe to run multiple times. After the first run, all values will already be encrypted by the
 * converter, so subsequent reads/writes are no-ops.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class KeyMigrationRunner {

  private final SimpleKeyEncryptionService encryptionService;
  private final JdbcTemplate jdbcTemplate;
  private final CryptoProperties cryptoProperties;

  @EventListener(ApplicationReadyEvent.class)
  public void migrateExistingKeys() {
    if (!encryptionService.isEncryptionActive()) {
      log.info("[KeyMigration] Encryption not active — skipping key migration");
      return;
    }

    log.info("[KeyMigration] Starting credential envelope migration...");

    int totalMigrated = 0;
    for (String column : APP_USER_SECRET_COLUMNS) {
      totalMigrated += migrateTable("app_user", column);
    }
    for (String column : ORG_SECRET_COLUMNS) {
      totalMigrated += migrateTable("org", column);
    }
    for (String column : PIPELINE_RUN_SECRET_COLUMNS) {
      totalMigrated += migrateTable("pipeline_run", column, "run_id");
    }
    totalMigrated += migrateTable("org_slack_integration", "bot_token");

    if (totalMigrated > 0) {
      log.info(
          "[KeyMigration] Re-wrote {} credential values into versioned envelopes", totalMigrated);
    } else {
      log.info("[KeyMigration] No plaintext/legacy credentials found — all already enveloped");
    }
  }

  /**
   * COMPLETE per-tenant secret column sets (2026-07-17 credential gate). The old hardcoded
   * 10-column list silently missed github_pat, bitbucket_app_password,
   * deepseek/fireworks/openrouter keys, and the Slack bot token. `personal_access_token.token_hash`
   * is deliberately absent — it is a one-way SHA-256 hash, not recoverable material.
   */
  static final java.util.List<String> APP_USER_SECRET_COLUMNS =
      java.util.List.of(
          "openai_api_key",
          "anthropic_api_key",
          "gemini_api_key",
          "deepseek_api_key",
          "fireworks_api_key",
          "jira_api_token",
          "confluence_api_token",
          "github_pat",
          "bitbucket_app_password");

  static final java.util.List<String> ORG_SECRET_COLUMNS =
      java.util.List.of(
          "openai_api_key",
          "anthropic_api_key",
          "gemini_api_key",
          "open_router_api_key",
          "deepseek_api_key",
          "fireworks_api_key",
          "jira_api_token",
          "confluence_api_token");

  /**
   * Legacy run snapshots still carry SCM credentials so an accepted asynchronous run can clone or
   * push after the request ends. Until those snapshots are replaced with a credential reference,
   * they require the same at-rest envelope as the account-level source.
   */
  static final java.util.List<String> PIPELINE_RUN_SECRET_COLUMNS =
      java.util.List.of("github_pat", "bitbucket_app_password");

  /**
   * Reads all non-null values from the given column and rewrites anything that is not yet a
   * versioned envelope: plaintext encrypts directly; legacy bare-Base64 ciphertext decrypts with
   * the current key and re-encrypts into the envelope. Already-enveloped rows are skipped exactly
   * by prefix (no heuristic), so the runner stays idempotent across boots.
   */
  private int migrateTable(String table, String column) {
    return migrateTable(table, column, "id");
  }

  private int migrateTable(String table, String column, String keyColumn) {
    try {
      // NOTE: use the no-element-type overload — queryForList(sql, Map.class) maps each row to a
      // SINGLE column and throws "Incorrect column count: expected 1, actual 2" for our two-column
      // (id, <column>) select. queryForList(sql) returns List<Map<String,Object>> as intended.
      List<java.util.Map<String, Object>> rows =
          jdbcTemplate.queryForList(
              "SELECT "
                  + keyColumn
                  + ", "
                  + column
                  + " FROM "
                  + table
                  + " WHERE "
                  + column
                  + " IS NOT NULL AND "
                  + column
                  + " != ''");

      int count = 0;
      for (var row : rows) {
        String value = (String) row.get(column);
        if (value == null || value.isBlank()) continue;
        if (encryptionService.isEnveloped(value)) continue;

        String plaintext = value;
        if (encryptionService.isLikelyEncrypted(value)) {
          // Legacy bare-Base64 ciphertext: recover the plaintext with the current key. The lenient
          // legacy path returns the input unchanged when it is not actually ciphertext — in that
          // ambiguous case we still envelope it (it was stored as-is, so it IS the credential).
          plaintext = encryptionService.decrypt(value);
        }

        String enveloped = encryptionService.encrypt(plaintext);
        jdbcTemplate.update(
            "UPDATE " + table + " SET " + column + " = ? WHERE " + keyColumn + " = ?",
            enveloped,
            row.get(keyColumn));
        count++;
      }

      if (count > 0) {
        log.info("[KeyMigration] Enveloped {} values in {}.{}", count, table, column);
      }
      return count;
    } catch (Exception e) {
      String message =
          "[KeyMigration] Failed to migrate " + table + "." + column + ": " + e.getMessage();
      if (cryptoProperties.isRequireEncryption()) {
        // In prod/benchmark, starting after a partial credential migration is worse than staying
        // down: converters may then encounter legacy/plaintext values, new writes can mix formats,
        // and operators receive a false "encryption required" guarantee. Propagate the original
        // cause through the ApplicationReadyEvent so Spring aborts startup visibly.
        throw new IllegalStateException(message, e);
      }
      log.warn(message);
      return 0;
    }
  }
}
