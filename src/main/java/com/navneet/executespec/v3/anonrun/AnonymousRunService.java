package com.navneet.executespec.v3.anonrun;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * R-111 follow-up — extracted business logic for the anonymous-trial endpoint. Splits {@link
 * com.navneet.executespec.v3.api.AnonymousRunController} into a thin HTTP shell + this testable
 * service so the anti-abuse rules can be unit tested without spinning up MockMvc.
 *
 * <p>Pipeline order (matters — each gate is cheaper than the next):
 *
 * <ol>
 *   <li>Pre-flight cost envelope: spec length cap (4_000 chars enforced at the DTO; this layer also
 *       rejects oversize via {@link #MAX_SPEC_CHARS} in case the cap drifts)
 *   <li>Per-IP rate limit (Redis, 3/hour)
 *   <li>Abuse heuristic (banned topics, prompt-injection markers)
 *   <li>Session minted + persisted to Redis (24h TTL)
 *   <li>Dispatch — stub by default; real LLM impl behind {@code v3.anonymous-trial.real-dispatch}
 *       flag
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnonymousRunService {

  public static final String FLAG_REAL_DISPATCH = "v3.anonymous-trial.real-dispatch";
  public static final int MAX_SPEC_CHARS = 4_000;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final AnonymousRunRateLimiter rateLimiter;
  private final AnonymousRunSessionStore sessionStore;
  private final AbuseHeuristic abuseHeuristic;
  private final AnonymousRunDispatcher dispatcher;
  private final FeatureFlagService featureFlags;

  /**
   * @return either an {@link Outcome.Accepted} containing the live session, or {@link
   *     Outcome.Rejected} with a structured reason. The controller maps these to 202/4xx HTTP
   *     responses.
   */
  public Outcome submit(String spec, String clientIp) {
    if (spec == null || spec.length() > MAX_SPEC_CHARS) {
      return Outcome.rejected(
          429,
          "spec_too_large",
          "Trim your spec to under "
              + MAX_SPEC_CHARS
              + " characters, or sign up to ship anything.");
    }

    if (!rateLimiter.tryConsume(clientIp)) {
      log.warn("v3 anon-run rate limit exceeded for ip={}", clientIp);
      return Outcome.rejected(
          429,
          "rate_limit",
          "You've used "
              + AnonymousRunRateLimiter.LIMIT_PER_HOUR
              + " free anonymous runs in the last hour. Sign up to keep going.");
    }

    AbuseHeuristic.Decision abuse = abuseHeuristic.check(spec);
    if (!abuse.allowed()) {
      log.warn("v3 anon-run abuse-heuristic rejected ip={} reason={}", clientIp, abuse.code());
      return Outcome.rejected(400, abuse.code(), abuse.message());
    }

    boolean realDispatch = featureFlags.isEnabled(FLAG_REAL_DISPATCH);
    String token = mintSessionToken();
    String runId = "anon-" + token.substring(0, 8);
    AnonymousRunSession session =
        new AnonymousRunSession(
            token,
            runId,
            spec,
            realDispatch ? "PLANNING" : "STUB_COMPLETE",
            realDispatch
                ? "Building your code — usually 60–90s. We'll show the diff when it's ready."
                : "Anonymous trial is gated this session — sign up to ship a real run.",
            Instant.now(),
            null,
            null);
    sessionStore.save(session);

    log.info(
        "v3 anon-run created token={} ip={} realDispatch={} specLen={}",
        token.substring(0, 8),
        clientIp,
        realDispatch,
        spec.length());

    if (realDispatch) {
      // Synchronous dispatch keeps the wire-point simple. The real impl
      // will swap this for a CompletableFuture-backed path so the HTTP
      // call returns 202 immediately and the result lands in the session
      // when the LLM finishes.
      try {
        AnonymousRunSession result = dispatcher.dispatch(session);
        sessionStore.save(result);
        return Outcome.accepted(result);
      } catch (RuntimeException dispatchErr) {
        log.warn("v3 anon-run dispatch failed token={}: {}", token, dispatchErr.getMessage());
        AnonymousRunSession failed =
            session.withStatus(
                "FAILED",
                "Something went wrong building this. Sign up to retry with full support.");
        sessionStore.save(failed);
        return Outcome.accepted(failed);
      }
    }

    return Outcome.accepted(session);
  }

  public Optional<AnonymousRunSession> findSession(String token) {
    return sessionStore.find(token);
  }

  /**
   * Bind a freshly-signed-up user to an anonymous session so the work they did before signup can
   * follow them in. Idempotent + write-once: see {@link AnonymousRunSessionStore#bindToUser}.
   */
  public Optional<AnonymousRunSession> bindToUser(String token, String userId) {
    return sessionStore.bindToUser(token, userId);
  }

  private static String mintSessionToken() {
    byte[] bytes = new byte[24];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public sealed interface Outcome {
    record Accepted(AnonymousRunSession session) implements Outcome {}

    record Rejected(int httpStatus, String code, String message) implements Outcome {}

    static Outcome accepted(AnonymousRunSession s) {
      return new Accepted(s);
    }

    static Outcome rejected(int status, String code, String msg) {
      return new Rejected(status, code, msg);
    }
  }
}
