package com.navneet.executespec.specchat.policy;

import static org.mockito.Mockito.mock;

import java.time.Clock;

/** Creates a real, bounded turn guard while isolating its resolver revalidation seam. */
public final class ProjectChatTurnGuardFixture {

  private ProjectChatTurnGuardFixture() {}

  public static ProjectChatPolicyResolver.TurnGuard allowing(ProjectChatPolicy policy) {
    return new ProjectChatPolicyResolver.TurnGuard(
        mock(ProjectChatPolicyResolver.class), policy, null, Clock.systemUTC());
  }
}
