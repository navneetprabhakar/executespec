package com.navneet.executespec.v3.anonrun;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * R-111 follow-up — default {@link AnonymousRunDispatcher}. Returns a "we'd love to build this —
 * sign up to ship it" response without spending any LLM tokens. This is what runs when {@code
 * v3.anonymous-trial.real-dispatch} is OFF, which is the production default until the cost-cap
 * design is signed off.
 */
@Slf4j
@Component
public class StubAnonymousRunDispatcher implements AnonymousRunDispatcher {

  @Override
  public AnonymousRunSession dispatch(AnonymousRunSession session) {
    log.info(
        "v3 anon-run stub-dispatch token={} specLen={}", session.token(), session.spec().length());
    return session.withStatus("STUB_COMPLETE", "Sign up to ship a real run with this spec.");
  }
}
