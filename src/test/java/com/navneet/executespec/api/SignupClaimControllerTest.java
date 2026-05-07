package com.navneet.executespec.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.anonrun.AnonymousRunService;
import com.navneet.executespec.anonrun.AnonymousRunSession;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class V3SignupClaimControllerTest {

  private AnonymousRunService anonymousRunService;
  private SignupClaimController controller;
  private AppUserDetails alice;
  private AppUserDetails bob;

  @BeforeEach
  void setUp() {
    anonymousRunService = mock(AnonymousRunService.class);
    controller = new SignupClaimController(anonymousRunService);

    AppUser aliceUser = AppUser.builder().id("u-alice").email("alice@test").build();
    AppUser bobUser = AppUser.builder().id("u-bob").email("bob@test").build();
    alice = mock(AppUserDetails.class);
    when(alice.getUser()).thenReturn(aliceUser);
    bob = mock(AppUserDetails.class);
    when(bob.getUser()).thenReturn(bobUser);
  }

  @Test
  void unauthenticated_caller_gets_401() {
    ResponseEntity<SignupClaimController.ClaimResponse> resp =
        controller.claimAnonymousSession(new SignupClaimController.ClaimRequest("tok-1"), null);
    assertEquals(401, resp.getStatusCode().value());
    verifyNoInteractions(anonymousRunService);
  }

  @Test
  void unknown_token_returns_200_with_session_not_found_reason() {
    when(anonymousRunService.bindToUser("missing", "u-alice")).thenReturn(Optional.empty());

    ResponseEntity<SignupClaimController.ClaimResponse> resp =
        controller.claimAnonymousSession(
            new SignupClaimController.ClaimRequest("missing"), alice);

    assertEquals(200, resp.getStatusCode().value());
    assertNotNull(resp.getBody());
    assertFalse(resp.getBody().claimed());
    assertEquals("session_not_found", resp.getBody().reason());
  }

  @Test
  void successful_bind_returns_claimed_true_with_run_id() {
    AnonymousRunSession bound =
        new AnonymousRunSession(
            "tok-1", "anon-tok-1", "spec", "STUB_COMPLETE", "msg", Instant.now(), null, "u-alice");
    when(anonymousRunService.bindToUser("tok-1", "u-alice")).thenReturn(Optional.of(bound));

    ResponseEntity<SignupClaimController.ClaimResponse> resp =
        controller.claimAnonymousSession(new SignupClaimController.ClaimRequest("tok-1"), alice);

    assertTrue(resp.getBody().claimed());
    assertEquals("tok-1", resp.getBody().sessionToken());
    assertEquals("anon-tok-1", resp.getBody().runId());
    assertNull(resp.getBody().reason());
  }

  @Test
  void session_already_bound_to_another_user_is_rejected_cleanly() {
    // Session already bound to bob; alice tries to claim it
    AnonymousRunSession existing =
        new AnonymousRunSession(
            "tok-1", "anon-tok-1", "spec", "STUB_COMPLETE", "msg", Instant.now(), null, "u-bob");
    when(anonymousRunService.bindToUser(eq("tok-1"), eq("u-alice")))
        .thenReturn(Optional.of(existing));

    ResponseEntity<SignupClaimController.ClaimResponse> resp =
        controller.claimAnonymousSession(new SignupClaimController.ClaimRequest("tok-1"), alice);

    assertFalse(resp.getBody().claimed(), "alice cannot claim a session already bound to bob");
    assertEquals("already_bound_to_another_user", resp.getBody().reason());
  }
}
