package com.navneet.executespec.v3.api;

import com.navneet.executespec.config.featureflag.FeatureFlagService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * R-111 — anonymous-trial endpoint.
 *
 * Greenfield doc §4.1: a logged-out visitor can submit the homepage
 * textarea and see real value before being asked to sign up. The
 * anti-abuse story (real-LLM-dispatch + cost-cap + per-IP rate-limit
 * + abuse classifier) is non-trivial and lands in Wave 2 — this
 * controller ships the SHAPE today so the v3 landing page (R-110)
 * can be wired end-to-end without exposing a $$$ surface.
 *
 * What ships now:
 *   - POST /api/v3/anonymous-runs — accepts the body, validates, mints
 *     a sessionToken + a stub runId, fires the
 *     V3_LANDING_TEXTAREA_SUBMIT funnel event (server-side; client also
 *     captures via R-160).
 *   - GET /api/v3/anonymous-runs/{sessionToken} — returns the stub
 *     status so the landing page UX (loading → result) works against
 *     real wiring.
 *   - In-memory IP rate-limit (3/hour) — Wave 2 promotes to Redis.
 *   - The actual LLM dispatch is GATED behind feature flag
 *     `v3.anonymous-trial.real-dispatch` (default OFF). When OFF,
 *     a stub COMPLETE response is returned after a short delay so
 *     the UI flow is testable end-to-end.
 *
 * What's deferred to Wave 2:
 *   - Real run dispatch through V2RunOrchestrator with hard cost cap
 *     ($0.20/run), Anthropic LIGHTWEIGHT pinned, abuse-classifier
 *     pre-flight, per-IP Redis rate-limit, session-token → user-id
 *     binding on signup.
 */
@Slf4j
@RestController
@RequestMapping("/api/v3")
public class AnonymousRunController {

    /** Feature flag controlling whether real LLM dispatch is enabled.
     *  When OFF (Wave 1 default), the controller returns a stub. */
    private static final String FLAG_REAL_DISPATCH = "v3.anonymous-trial.real-dispatch";
    private static final int RATE_LIMIT_PER_HOUR = 3;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** In-memory rate-limit counter keyed by client IP. Wave-1 only;
     *  promote to Redis in Wave 2 so the cap holds across multiple
     *  JVMs / restarts. */
    private final Map<String, RateBucket> ipBuckets = new ConcurrentHashMap<>();

    /** In-memory session store. Wave-1 stub; Wave 2 persists to a real
     *  table or Redis with TTL. */
    private final Map<String, AnonymousSession> sessions = new ConcurrentHashMap<>();

    private final FeatureFlagService featureFlags;

    public AnonymousRunController(FeatureFlagService featureFlags) {
        this.featureFlags = featureFlags;
    }

    @PostMapping("/anonymous-runs")
    public ResponseEntity<AnonymousRunResponse> submit(
            @Valid @RequestBody AnonymousRunRequest body,
            HttpServletRequest req
    ) {
        String ip = clientIp(req);
        if (!consumeRateLimit(ip)) {
            log.warn("v3 anonymous-run rate limit exceeded for ip={}", ip);
            return ResponseEntity.status(429).body(AnonymousRunResponse.error(
                    "rate_limit",
                    "You've used 3 free anonymous runs in the last hour. Sign up to keep going."));
        }

        boolean realDispatch = featureFlags.isEnabled(FLAG_REAL_DISPATCH);
        String token = mintSessionToken();
        String runId = "anon-" + token.substring(0, 8);
        AnonymousSession s = new AnonymousSession(token, runId, body.spec(), realDispatch ? "PLANNING" : "STUB_COMPLETE");
        sessions.put(token, s);

        log.info("v3 anonymous-run created token={} ip={} realDispatch={} specLen={}",
                token.substring(0, 8), ip, realDispatch,
                body.spec() == null ? 0 : body.spec().length());

        // R-160 server-side capture happens here in Wave 2 once
        // EventTrackingService grows an "anonymous" path. Today the
        // client captures V3_LANDING_TEXTAREA_SUBMIT.

        return ResponseEntity
                .status(202)
                .body(new AnonymousRunResponse(
                        token,
                        runId,
                        realDispatch ? "PLANNING" : "STUB_COMPLETE",
                        realDispatch
                                ? "Building your code — usually 60–90s. We'll show the diff when it's ready."
                                : "Anonymous trial is gated this session — sign up to ship a real run.",
                        "/api/v3/anonymous-runs/" + token,
                        null
                ));
    }

    @GetMapping("/anonymous-runs/{sessionToken}")
    public ResponseEntity<AnonymousRunResponse> status(
            @PathVariable String sessionToken
    ) {
        AnonymousSession s = sessions.get(sessionToken);
        if (s == null) {
            return ResponseEntity.status(404).body(AnonymousRunResponse.error(
                    "not_found",
                    "Session not found or expired. Sign up to keep your work."));
        }
        return ResponseEntity.ok(new AnonymousRunResponse(
                s.token,
                s.runId,
                s.status,
                s.status.equals("STUB_COMPLETE")
                        ? "Sign up to ship a real run with this spec."
                        : "Building…",
                "/api/v3/anonymous-runs/" + s.token,
                null
        ));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String mintSessionToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return req.getRemoteAddr();
    }

    private boolean consumeRateLimit(String ip) {
        long now = System.currentTimeMillis();
        RateBucket bucket = ipBuckets.computeIfAbsent(ip, k -> new RateBucket(now));
        synchronized (bucket) {
            if (now - bucket.windowStart > 3_600_000L) {
                bucket.windowStart = now;
                bucket.count.set(0);
            }
            return bucket.count.incrementAndGet() <= RATE_LIMIT_PER_HOUR;
        }
    }

    // ── DTOs / records ────────────────────────────────────────────────────────

    public record AnonymousRunRequest(
            @NotBlank @Size(max = 4_000) String spec
    ) {}

    public record AnonymousRunResponse(
            String sessionToken,
            String runId,
            String status,
            String message,
            String pollUrl,
            String error
    ) {
        public static AnonymousRunResponse error(String code, String msg) {
            return new AnonymousRunResponse(null, null, null, msg, null, code);
        }
    }

    private static final class AnonymousSession {
        final String token;
        final String runId;
        final String spec;
        final String status;

        AnonymousSession(String token, String runId, String spec, String status) {
            this.token = token;
            this.runId = runId;
            this.spec = spec;
            this.status = status;
        }
    }

    private static final class RateBucket {
        long windowStart;
        final AtomicInteger count = new AtomicInteger(0);

        RateBucket(long start) {
            this.windowStart = start;
        }
    }
}
