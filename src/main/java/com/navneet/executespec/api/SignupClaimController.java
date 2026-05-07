package com.navneet.executespec.api;

import java.util.Optional;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.anonrun.AnonymousRunService;
import com.navneet.executespec.anonrun.AnonymousRunSession;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * R-111 + R-162 follow-up — post-signup claim endpoint.
 *
 * <p>Closes the loop between the email magic-link / anonymous-trial token surfaces and the
 * freshly-authenticated user. The referral side is already auto-claimed inside {@code
 * AuthService.register} + {@code findOrCreateFromOAuth} via email match, so the only reason this
 * endpoint exists is to claim an anonymous-trial session by token (the token isn't on the user
 * record — it's in sessionStorage on the client until they sign up).
 *
 * <p>Idempotent. Authenticated. Safe to call from anywhere on the v3 surface after signup; the
 * typical caller is the V3Auth post-login effect or the OAuth-return landing page.
 */
@Slf4j
@RestController
@RequestMapping("/api/v3/auth")
@RequiredArgsConstructor
public class SignupClaimController {

  private final AnonymousRunService anonymousRunService;

  @PostMapping("/claim-anonymous-session")
  public ResponseEntity<ClaimResponse> claimAnonymousSession(
      @Valid @RequestBody ClaimRequest body, @AuthenticationPrincipal AppUserDetails userDetails) {
    if (userDetails == null) return ResponseEntity.status(401).build();
    String userId = userDetails.getUser().getId();

    Optional<AnonymousRunSession> bound =
        anonymousRunService.bindToUser(body.sessionToken(), userId);
    if (bound.isEmpty()) {
      // Token unknown / expired — not a hard error; the visitor's
      // session may have rolled over its 24h TTL while they took
      // their time signing up.
      log.info("v3 anon-run claim: token not found user={}", userId);
      return ResponseEntity.ok(new ClaimResponse(false, null, null, "session_not_found"));
    }
    AnonymousRunSession s = bound.get();
    boolean nowMine = userId.equals(s.boundUserId());
    return ResponseEntity.ok(
        new ClaimResponse(
            nowMine, s.token(), s.runId(), nowMine ? null : "already_bound_to_another_user"));
  }

  public record ClaimRequest(@NotBlank String sessionToken) {}

  public record ClaimResponse(boolean claimed, String sessionToken, String runId, String reason) {}
}
