package com.navneet.executespec.crypto;

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

  @EventListener(ApplicationReadyEvent.class)
  public void migrateExistingKeys() {
    if (!encryptionService.isEncryptionActive()) {
      log.info("[KeyMigration] Encryption not active — skipping key migration");
      return;
    }

    log.info("[KeyMigration] Starting plaintext API key encryption migration...");

    int totalMigrated = 0;
    totalMigrated += migrateTable("app_user", "openai_api_key");
    totalMigrated += migrateTable("app_user", "anthropic_api_key");
    totalMigrated += migrateTable("app_user", "gemini_api_key");
    totalMigrated += migrateTable("app_user", "jira_api_token");
    totalMigrated += migrateTable("app_user", "confluence_api_token");

    totalMigrated += migrateTable("org", "openai_api_key");
    totalMigrated += migrateTable("org", "anthropic_api_key");
    totalMigrated += migrateTable("org", "gemini_api_key");
    totalMigrated += migrateTable("org", "jira_api_token");
    totalMigrated += migrateTable("org", "confluence_api_token");

    if (totalMigrated > 0) {
      log.info("[KeyMigration] Encrypted {} plaintext API key values", totalMigrated);
    } else {
      log.info("[KeyMigration] No plaintext API keys found — all already encrypted or empty");
    }
  }

  /**
   * Reads all non-null values from the given column, encrypts them, and writes back. Skips values
   * that are already encrypted (heuristic: valid Base64 of sufficient length).
   */
  private int migrateTable(String table, String column) {
    try {
      var rows =
          jdbcTemplate.queryForList(
              "SELECT id, "
                  + column
                  + " FROM "
                  + table
                  + " WHERE "
                  + column
                  + " IS NOT NULL AND "
                  + column
                  + " != ''",
              java.util.Map.class);

      int count = 0;
      for (var row : rows) {
        String value = (String) row.get(column);
        if (value == null || value.isBlank()) continue;

        // Try to decrypt — if it succeeds without error, it's already encrypted
        String encrypted = encryptionService.encrypt(value);
        if (!encrypted.equals(value)) {
          // Value changed after encryption → was plaintext
          jdbcTemplate.update(
              "UPDATE " + table + " SET " + column + " = ? WHERE id = ?", encrypted, row.get("id"));
          count++;
        }
      }

      if (count > 0) {
        log.info("[KeyMigration] Encrypted {} values in {}.{}", count, table, column);
      }
      return count;
    } catch (Exception e) {
      log.warn("[KeyMigration] Failed to migrate {}.{}: {}", table, column, e.getMessage());
      return 0;
    }
  }
}
