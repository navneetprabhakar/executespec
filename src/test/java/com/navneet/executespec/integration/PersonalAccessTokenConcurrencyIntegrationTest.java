package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.navneet.executespec.auth.dto.CreatePatRequest;
import com.navneet.executespec.auth.service.PersonalAccessTokenService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Proves the active-token cap under ten concurrent real PostgreSQL transactions. */
class PersonalAccessTokenConcurrencyIntegrationTest extends PostgresIntegrationBase {

  @Autowired private PersonalAccessTokenService tokens;
  @Autowired private JdbcTemplate jdbc;

  private final String userId = "pat-race-" + UUID.randomUUID();

  @AfterEach
  void cleanup() {
    jdbc.update("DELETE FROM personal_access_token WHERE user_id=?", userId);
    jdbc.update("DELETE FROM app_user WHERE id=?", userId);
  }

  @Test
  void tenConcurrentFreeTierCreatesConvergeToOneActiveToken() throws Exception {
    jdbc.update(
        "INSERT INTO app_user"
            + " (id,email,first_name,password_hash,tier,status) VALUES (?,?,?,?,?,?)",
        userId,
        userId + "@example.com",
        "PAT",
        "hash",
        "FREE",
        "ACTIVE");

    int callers = 10;
    CountDownLatch ready = new CountDownLatch(callers);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> results = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(callers)) {
      for (int i = 0; i < callers; i++) {
        int index = i;
        results.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("start barrier timed out");
                  }
                  return tokens.create(userId, new CreatePatRequest("device-" + index, null));
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      for (Future<?> result : results) result.get(30, TimeUnit.SECONDS);
    }

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM personal_access_token"
                    + " WHERE user_id=? AND revoked_at IS NULL"
                    + " AND (expires_at IS NULL OR expires_at>NOW())",
                Long.class,
                userId))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM personal_access_token WHERE user_id=?", Long.class, userId))
        .isEqualTo(10L);
  }

  @Test
  void resolvingActiveTokenPersistsLastUsedTimestamp() {
    jdbc.update(
        "INSERT INTO app_user"
            + " (id,email,first_name,password_hash,tier,status) VALUES (?,?,?,?,?,?)",
        userId,
        userId + "@example.com",
        "PAT",
        "hash",
        "FREE",
        "ACTIVE");

    PersonalAccessTokenService.CreatedToken created =
        tokens.create(userId, new CreatePatRequest("last-used", null));

    assertThat(tokens.resolveUserId(created.plaintext())).isEqualTo(userId);
    assertThat(
            jdbc.queryForObject(
                "SELECT last_used_at IS NOT NULL FROM personal_access_token WHERE id=?",
                Boolean.class,
                created.token().getId()))
        .isTrue();
  }
}
