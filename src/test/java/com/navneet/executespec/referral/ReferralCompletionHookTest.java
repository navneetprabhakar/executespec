package com.navneet.executespec.referral;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * R-162 — wiring test for the RunOrchestrator → ReferralService stitch.
 *
 * <p>The hook itself is 4 lines of conditional logic, but it sits at the exact intersection of "run
 * lifecycle" and "growth loop", so it gets a dedicated test that:
 *
 * <ul>
 *   <li>proves the grant fires on COMPLETE + owned runs (the happy path a referee triggers when
 *       their first run reaches READY)
 *   <li>proves it does NOT fire on non-terminal or non-COMPLETE statuses
 *   <li>proves it does NOT fire on anonymous / system runs (no owner)
 *   <li>proves a referral-subsystem exception never escapes (terminate must not fail because of a
 *       referral side-effect)
 * </ul>
 *
 * <p>This is the integration-shaped test the user explicitly asked for in the burn-down — extracted
 * to a helper so it can be exercised without standing up RunOrchestrator's 43 collaborators.
 */
class ReferralCompletionHookTest {

  private ReferralService referralService;
  private ReferralCompletionHook hook;

  @BeforeEach
  void setUp() {
    referralService = mock(ReferralService.class);
    hook = new ReferralCompletionHook(referralService);
  }

  @Test
  void fires_grant_on_complete_status_with_owner() {
    when(referralService.onRefereeFirstReadyRun("u-bob")).thenReturn(true);

    boolean attempted = hook.onTerminalStatus("run-1", "COMPLETE", "u-bob");

    assertTrue(attempted, "hook must report it attempted the grant on COMPLETE+owner");
    verify(referralService).onRefereeFirstReadyRun("u-bob");
  }

  @Test
  void does_not_fire_when_status_is_failed() {
    boolean attempted = hook.onTerminalStatus("run-1", "FAILED", "u-bob");

    assertFalse(attempted);
    verify(referralService, never()).onRefereeFirstReadyRun(anyString());
  }

  @Test
  void does_not_fire_when_status_is_cancelled() {
    boolean attempted = hook.onTerminalStatus("run-1", "CANCELLED", "u-bob");

    assertFalse(attempted);
    verify(referralService, never()).onRefereeFirstReadyRun(anyString());
  }

  @Test
  void does_not_fire_when_status_is_partial() {
    // PARTIAL is still a terminal state but it's NOT a successful one —
    // referral grant must wait for a clean COMPLETE so the referee is
    // proving they'll actually use the product.
    boolean attempted = hook.onTerminalStatus("run-1", "PARTIAL", "u-bob");

    assertFalse(attempted);
    verify(referralService, never()).onRefereeFirstReadyRun(anyString());
  }

  @Test
  void does_not_fire_for_anonymous_or_system_run_with_null_owner() {
    boolean attempted = hook.onTerminalStatus("run-1", "COMPLETE", null);

    assertFalse(attempted, "anonymous/system runs (no owner) must not credit anyone");
    verify(referralService, never()).onRefereeFirstReadyRun(anyString());
  }

  @Test
  void swallows_referral_subsystem_exception_so_terminate_path_is_unaffected() {
    when(referralService.onRefereeFirstReadyRun("u-bob"))
        .thenThrow(new RuntimeException("DB connection lost"));

    // Must not throw — terminate() depends on this contract
    boolean attempted = hook.onTerminalStatus("run-1", "COMPLETE", "u-bob");

    assertTrue(attempted, "even when the referral path errors, the attempt counts (logged)");
    verify(referralService).onRefereeFirstReadyRun("u-bob");
  }

  @Test
  void terminal_trigger_status_is_pinned_to_complete() {
    // Belt-and-suspenders: if someone changes TERMINAL_TRIGGER_STATUS to
    // include PARTIAL or FAILED, the test for "does not fire" cases will
    // catch it — but this guard makes the intent explicit at the level
    // of the constant.
    assertEquals(
        "COMPLETE",
        ReferralCompletionHook.TERMINAL_TRIGGER_STATUS,
        "referral grant must remain pinned to clean COMPLETE only");
  }
}
