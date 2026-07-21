package com.navneet.executespec.anonrun;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * R-111 follow-up — Redis-backed session store for anonymous-trial sessions. Replaces the in-memory
 * ConcurrentHashMap that lost state on JVM restart and couldn't be inspected operationally.
 *
 * <p>Keying: {@code v3:anon-run:sess:<token>}. JSON-encoded value, 24h TTL applied on every write
 * so an active session that gets updated extends its lifetime.
 *
 * <p>Bind-on-signup: {@link #bindToUser(String, String)} stamps boundUserId so the auth flow can
 * claim the session at signup time. The downstream "convert this anonymous run into a real run"
 * step is the next deferred slice.
 *
 * <p><b>RR-665 (2026-07-21) — the session token is a bearer credential.</b> Two corrections:
 *
 * <ol>
 *   <li><b>The bearer never reaches a log.</b> Every diagnostic here identifies a session by {@link
 *       #fingerprint(String)} — a truncated SHA-256 of the token — so an operator can correlate
 *       lines without the log line itself being a usable credential. Anything that appears in
 *       application logs must be assumed to have leaked into diagnostic pipelines.
 *   <li><b>Binding is atomic.</b> The old implementation read the session, checked {@code
 *       boundUserId}, then wrote — so two concurrent claims of the same unbound token could both
 *       observe it as unclaimed, both be told they succeeded, and race to overwrite the owner.
 *       Ownership is now decided by a single {@code SETNX} on a separate claim key ({@code
 *       v3:anon-run:claim:<token>}) which is the authoritative record of who owns the session; the
 *       session blob is updated only by the caller that won that claim. Exactly one claimant can
 *       ever win, and every other caller is told the authoritative owner.
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnonymousRunSessionStore {

  private static final String KEY_PREFIX = "v3:anon-run:sess:";

  /**
   * RR-665 — claim-if-unbound key. Separate from the session blob precisely so ownership can be
   * decided by one atomic Redis operation rather than a read-check-write over the whole record.
   */
  private static final String CLAIM_PREFIX = "v3:anon-run:claim:";

  public static final Duration SESSION_TTL = Duration.ofHours(24);

  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;

  public void save(AnonymousRunSession session) {
    try {
      redis
          .opsForValue()
          .set(KEY_PREFIX + session.token(), mapper.writeValueAsString(session), SESSION_TTL);
    } catch (JsonProcessingException e) {
      // Should never trip — record fields are all primitives + strings.
      // Log loud + rethrow so the controller surfaces a 500 instead of
      // silently losing the session.
      log.error(
          "v3 anon-run session serialize failed session={}: {}",
          fingerprint(session.token()),
          e.getMessage());
      throw new IllegalStateException("session serialize failed", e);
    }
  }

  public Optional<AnonymousRunSession> find(String token) {
    if (token == null || token.isBlank()) return Optional.empty();
    String raw = redis.opsForValue().get(KEY_PREFIX + token);
    if (raw == null) return Optional.empty();
    try {
      return Optional.of(mapper.readValue(raw, AnonymousRunSession.class));
    } catch (JsonProcessingException e) {
      log.warn(
          "v3 anon-run session deserialize failed session={}: {}",
          fingerprint(token),
          e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Claim the session for a freshly-signed-up user. Returns the session as the caller may see it,
   * or empty when the token is unknown / expired.
   *
   * <p>Ownership is decided by one atomic claim-if-unbound. The returned session always carries the
   * authoritative {@code boundUserId}: when the caller lost the race (or the session was already
   * bound), that is the OTHER user's id, so the caller can see it did not win. Idempotent — a
   * second bind by the same user is a no-op that reports success.
   */
  public Optional<AnonymousRunSession> bindToUser(String token, String userId) {
    if (token == null || token.isBlank() || userId == null || userId.isBlank()) {
      return Optional.empty();
    }
    Optional<AnonymousRunSession> existing = find(token);
    if (existing.isEmpty()) return Optional.empty();
    AnonymousRunSession s = existing.get();

    String claimKey = CLAIM_PREFIX + token;
    // Sessions bound before the claim key existed: seed the claim from the blob so the CAS below
    // is authoritative for them too. setIfAbsent, so this cannot overwrite a live claim.
    if (s.boundUserId() != null) {
      redis.opsForValue().setIfAbsent(claimKey, s.boundUserId(), SESSION_TTL);
    }

    String owner = claim(claimKey, userId);
    if (owner == null) {
      // The claim key expired between our SETNX and the read-back. Retry once; if it is still
      // indeterminate, refuse rather than guess — an unproven claim must not grant ownership.
      owner = claim(claimKey, userId);
    }
    if (owner == null) {
      log.warn("v3 anon-run claim indeterminate session={}", fingerprint(token));
      return existing;
    }

    if (!owner.equals(userId)) {
      log.warn(
          "v3 anon-run session={} already bound to {} — rejecting bind to {}",
          fingerprint(token),
          owner,
          userId);
      // Report the authoritative owner even if the stored blob predates the claim key.
      return Optional.of(owner.equals(s.boundUserId()) ? s : s.boundTo(owner));
    }
    if (owner.equals(s.boundUserId())) {
      return existing; // idempotent — we already own it
    }
    // We won the claim; re-read so a concurrent status update from the dispatcher is preserved.
    AnonymousRunSession updated = find(token).orElse(s).boundTo(owner);
    save(updated);
    log.info("v3 anon-run session={} bound to user={}", fingerprint(token), userId);
    return Optional.of(updated);
  }

  /**
   * One atomic claim-if-unbound. Returns the authoritative owner (this caller when the claim was
   * won, the incumbent otherwise), or null when the key could not be resolved.
   */
  private String claim(String claimKey, String userId) {
    Boolean won = redis.opsForValue().setIfAbsent(claimKey, userId, SESSION_TTL);
    if (Boolean.TRUE.equals(won)) return userId;
    return redis.opsForValue().get(claimKey);
  }

  /**
   * RR-665 — a short, non-reversible identifier for a session, safe to log. Never log the token
   * itself: it is a bearer credential and a logged credential is a leaked credential.
   */
  static String fingerprint(String token) {
    if (token == null || token.isBlank()) return "none";
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest).substring(0, 12);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandated by the JCA spec; unreachable on any supported JVM.
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
