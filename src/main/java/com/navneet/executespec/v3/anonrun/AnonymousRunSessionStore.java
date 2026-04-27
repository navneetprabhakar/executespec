package com.navneet.executespec.v3.anonrun;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * R-111 follow-up — Redis-backed session store for anonymous-trial sessions.
 * Replaces the in-memory ConcurrentHashMap that lost state on JVM restart and
 * couldn't be inspected operationally.
 *
 * Keying: {@code v3:anon-run:sess:<token>}. JSON-encoded value, 24h TTL applied
 * on every write so an active session that gets updated extends its lifetime.
 *
 * Bind-on-signup: {@link #bindToUser(String, String)} stamps boundUserId so the
 * auth flow can claim the session at signup time. The downstream "convert this
 * anonymous run into a real run" step is the next deferred slice.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnonymousRunSessionStore {

    private static final String KEY_PREFIX = "v3:anon-run:sess:";
    public static final Duration SESSION_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public void save(AnonymousRunSession session) {
        try {
            redis.opsForValue().set(
                    KEY_PREFIX + session.token(),
                    mapper.writeValueAsString(session),
                    SESSION_TTL);
        } catch (JsonProcessingException e) {
            // Should never trip — record fields are all primitives + strings.
            // Log loud + rethrow so the controller surfaces a 500 instead of
            // silently losing the session.
            log.error("v3 anon-run session serialize failed token={}: {}",
                    session.token(), e.getMessage());
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
            log.warn("v3 anon-run session deserialize failed token={}: {}", token, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Stamp the session as "claimed" by a freshly-signed-up user. Returns the
     * updated session (or empty when the token is unknown / expired). Idempotent:
     * a second bind to the same userId is a no-op; a bind to a different userId
     * is rejected (returns the existing session unchanged) so a token can't be
     * silently re-claimed by another account.
     */
    public Optional<AnonymousRunSession> bindToUser(String token, String userId) {
        Optional<AnonymousRunSession> existing = find(token);
        if (existing.isEmpty()) return Optional.empty();
        AnonymousRunSession s = existing.get();
        if (s.boundUserId() != null && !s.boundUserId().equals(userId)) {
            log.warn("v3 anon-run session token={} already bound to {} — rejecting bind to {}",
                    token, s.boundUserId(), userId);
            return existing;
        }
        if (userId.equals(s.boundUserId())) {
            return existing; // idempotent
        }
        AnonymousRunSession updated = s.boundTo(userId);
        save(updated);
        log.info("v3 anon-run session token={} bound to user={}", token, userId);
        return Optional.of(updated);
    }
}
