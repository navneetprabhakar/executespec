package com.navneet.executespec.anonrun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * RR-665 — anonymous-session binding must be an atomic claim, and the bearer must never be logged
 * or echoed.
 *
 * <p>The Redis double is backed by a real {@link ConcurrentHashMap}, so {@code setIfAbsent} has
 * genuine compare-and-set semantics. A mock that always answered {@code true} would make the
 * concurrency test vacuous.
 */
class AnonymousRunSessionStoreTest {

  private static final String TOKEN = "anon-bearer-token-abcdefghijklmnop";

  private ConcurrentHashMap<String, String> redisData;
  private AnonymousRunSessionStore store;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    redisData = new ConcurrentHashMap<>();
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);

    when(ops.get(anyString())).thenAnswer(inv -> redisData.get(inv.<String>getArgument(0)));
    when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
        .thenAnswer(inv -> redisData.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);
    org.mockito.Mockito.doAnswer(
            inv -> {
              redisData.put(inv.getArgument(0), inv.getArgument(1));
              return null;
            })
        .when(ops)
        .set(anyString(), anyString(), any(Duration.class));

    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    store = new AnonymousRunSessionStore(redis, mapper);
    store.save(session(null));
  }

  private static AnonymousRunSession session(String boundUserId) {
    return new AnonymousRunSession(
        TOKEN, "anon-run-1", "spec", "STUB_COMPLETE", "msg", Instant.now(), null, boundUserId);
  }

  @Test
  void unbound_session_binds_to_the_caller() {
    Optional<AnonymousRunSession> bound = store.bindToUser(TOKEN, "u-alice");
    assertTrue(bound.isPresent());
    assertEquals("u-alice", bound.get().boundUserId());
  }

  @Test
  void second_bind_by_the_same_user_is_idempotent() {
    store.bindToUser(TOKEN, "u-alice");
    Optional<AnonymousRunSession> again = store.bindToUser(TOKEN, "u-alice");
    assertTrue(again.isPresent());
    assertEquals("u-alice", again.get().boundUserId());
  }

  @Test
  void a_second_account_cannot_take_over_a_bound_session() {
    store.bindToUser(TOKEN, "u-alice");
    Optional<AnonymousRunSession> bob = store.bindToUser(TOKEN, "u-bob");
    assertTrue(bob.isPresent());
    assertEquals(
        "u-alice",
        bob.get().boundUserId(),
        "bob must be told alice owns it, not handed the session");
    assertEquals("u-alice", store.find(TOKEN).orElseThrow().boundUserId());
  }

  /**
   * A session bound before the claim key existed still has exactly one owner: the legacy blob seeds
   * the claim, so a later caller cannot win a fresh claim over it.
   */
  @Test
  void legacy_bound_session_without_a_claim_key_is_still_protected() {
    store.save(session("u-alice"));
    redisData.remove("v3:anon-run:claim:" + TOKEN);

    Optional<AnonymousRunSession> bob = store.bindToUser(TOKEN, "u-bob");
    assertEquals("u-alice", bob.orElseThrow().boundUserId());
    assertEquals("u-alice", store.find(TOKEN).orElseThrow().boundUserId());
  }

  /** The original defect: concurrent claims of one unbound token could both be told they won. */
  @Test
  void concurrent_claims_produce_exactly_one_winner() throws Exception {
    int threads = 16;
    CyclicBarrier gate = new CyclicBarrier(threads);
    CountDownLatch done = new CountDownLatch(threads);
    Map<String, AtomicInteger> selfReportedWins = new ConcurrentHashMap<>();

    for (int i = 0; i < threads; i++) {
      String userId = "u-" + i;
      selfReportedWins.put(userId, new AtomicInteger());
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  gate.await(5, TimeUnit.SECONDS);
                  Optional<AnonymousRunSession> r = store.bindToUser(TOKEN, userId);
                  if (r.isPresent() && userId.equals(r.get().boundUserId())) {
                    selfReportedWins.get(userId).incrementAndGet();
                  }
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                } finally {
                  done.countDown();
                }
              });
    }
    assertTrue(done.await(15, TimeUnit.SECONDS), "binds did not finish");

    List<String> winners =
        selfReportedWins.entrySet().stream()
            .filter(e -> e.getValue().get() > 0)
            .map(Map.Entry::getKey)
            .toList();
    assertEquals(1, winners.size(), "exactly one caller may be told the claim succeeded");
    assertEquals(
        winners.get(0),
        store.find(TOKEN).orElseThrow().boundUserId(),
        "the persisted owner must be the caller that was told it won");
  }

  @Test
  void unknown_or_blank_token_binds_nothing() {
    assertTrue(store.bindToUser("no-such-token", "u-alice").isEmpty());
    assertTrue(store.bindToUser("", "u-alice").isEmpty());
    assertTrue(store.bindToUser(null, "u-alice").isEmpty());
    assertTrue(store.bindToUser(TOKEN, null).isEmpty());
  }

  /** RR-665 — the log/diagnostic identifier must not be the bearer or a usable prefix of it. */
  @Test
  void fingerprint_is_stable_and_does_not_reveal_the_token() {
    String fp = AnonymousRunSessionStore.fingerprint(TOKEN);
    assertEquals(fp, AnonymousRunSessionStore.fingerprint(TOKEN));
    assertNotEquals(TOKEN, fp);
    assertFalse(TOKEN.contains(fp), "fingerprint must not be a substring of the bearer");
    assertNotEquals(fp, AnonymousRunSessionStore.fingerprint(TOKEN + "x"));
    assertEquals("none", AnonymousRunSessionStore.fingerprint(null));
  }
}
