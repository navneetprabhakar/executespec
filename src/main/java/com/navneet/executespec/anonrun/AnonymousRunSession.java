package com.navneet.executespec.anonrun;

import java.time.Instant;

/**
 * Persisted shape of an anonymous-trial session. Lives in Redis with a 24h TTL (long enough for a
 * visitor to come back, sign up, and bind the resulting work; short enough that abandoned sessions
 * don't accumulate forever).
 *
 * @param token URL-safe random 24-byte token (Base64 no-padding)
 * @param runId synthetic run id for the UI to poll against
 * @param spec the textarea body the visitor submitted
 * @param status PLANNING | STUB_COMPLETE | COMPLETE | REJECTED_ABUSE | REJECTED_TOO_LARGE | FAILED
 * @param message human-readable status copy
 * @param createdAt when the session was minted
 * @param resultJson nullable; populated once the dispatcher writes a result
 * @param boundUserId nullable; populated when the visitor signs up + binds the session
 */
public record AnonymousRunSession(
    String token,
    String runId,
    String spec,
    String status,
    String message,
    Instant createdAt,
    String resultJson,
    String boundUserId) {
  public AnonymousRunSession withStatus(String newStatus, String newMessage) {
    return new AnonymousRunSession(
        token, runId, spec, newStatus, newMessage, createdAt, resultJson, boundUserId);
  }

  public AnonymousRunSession withResult(String newStatus, String newMessage, String result) {
    return new AnonymousRunSession(
        token, runId, spec, newStatus, newMessage, createdAt, result, boundUserId);
  }

  public AnonymousRunSession boundTo(String userId) {
    return new AnonymousRunSession(
        token, runId, spec, status, message, createdAt, resultJson, userId);
  }
}
