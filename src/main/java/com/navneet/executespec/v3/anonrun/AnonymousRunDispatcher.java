package com.navneet.executespec.v3.anonrun;

/**
 * R-111 follow-up — pluggable dispatch path for the anonymous-trial endpoint.
 *
 * The default impl ({@link StubAnonymousRunDispatcher}) returns a synthetic
 * "Sign up to ship a real run" response and is what runs whenever the
 * {@code v3.anonymous-trial.real-dispatch} feature flag is OFF (default). When
 * the flag is ON, a real impl can be registered as a {@code @Primary @Bean}
 * to take over without touching the controller / service.
 *
 * The real impl is intentionally NOT shipped here — wiring it requires:
 *   <ul>
 *     <li>Anthropic LIGHTWEIGHT routing through {@link com.navneet.executespec.v2.agent.LlmRouterV2}
 *         with a synthetic runId so cost-tracking still works</li>
 *     <li>Hard per-call cost cap of $0.01 (LIGHTWEIGHT class) enforced in the dispatcher</li>
 *     <li>An "outline-only" prompt — we want value without burning a real workspace
 *         per anonymous request (the goal is "show me what you'd build", not "ship the code")</li>
 *     <li>Operational dashboard for total spend on the anon trial path</li>
 *   </ul>
 */
public interface AnonymousRunDispatcher {

    /**
     * Synchronously process an anonymous spec. Implementations MUST be cheap
     * (LIGHTWEIGHT-tier or stub). The session passed in has already cleared
     * rate-limit + abuse-heuristic + cost-envelope checks.
     *
     * @return the updated session with status set to {@code COMPLETE} or
     *         {@code FAILED}; never null.
     */
    AnonymousRunSession dispatch(AnonymousRunSession session);
}
