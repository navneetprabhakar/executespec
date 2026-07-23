package com.navneet.executespec.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The lifecycle graph is the contract, so it is pinned explicitly rather than derived from the
 * implementation it is meant to constrain.
 */
class SandboxStateMachineTest {

  @Test
  void legalTransitionsAreExactlyTheDocumentedGraph() {
    assertThat(SandboxState.REQUESTED.allowedNext())
        .containsExactlyInAnyOrder(
            SandboxState.PROVISIONING,
            SandboxState.CANCELLED,
            SandboxState.EXPIRED,
            SandboxState.FAILED);
    assertThat(SandboxState.PROVISIONING.allowedNext())
        .containsExactlyInAnyOrder(
            SandboxState.READY,
            SandboxState.CANCEL_REQUESTED,
            SandboxState.RELEASING,
            SandboxState.FAILED);
    assertThat(SandboxState.READY.allowedNext())
        .containsExactlyInAnyOrder(
            SandboxState.CANCEL_REQUESTED, SandboxState.RELEASING, SandboxState.FAILED);
    assertThat(SandboxState.CANCEL_REQUESTED.allowedNext())
        .containsExactlyInAnyOrder(
            SandboxState.RELEASING, SandboxState.CANCELLED, SandboxState.FAILED);
    assertThat(SandboxState.RELEASING.allowedNext())
        .containsExactlyInAnyOrder(
            SandboxState.TERMINATED,
            SandboxState.CANCELLED,
            SandboxState.EXPIRED,
            SandboxState.FAILED);
  }

  @Test
  void terminalStatesAcceptNoFurtherTransition() {
    for (SandboxState state : SandboxState.values()) {
      if (!state.terminal()) {
        continue;
      }
      assertThat(state.allowedNext()).as("%s is terminal", state).isEmpty();
      for (SandboxState next : SandboxState.values()) {
        assertThat(state.canTransitionTo(next)).as("%s -> %s", state, next).isFalse();
      }
    }
  }

  @Test
  void illegalTransitionsAreRejectedWithBothEndpointsNamed() {
    assertThatThrownBy(() -> SandboxState.REQUESTED.requireTransitionTo(SandboxState.READY))
        .isInstanceOf(IllegalSandboxTransitionException.class)
        .hasMessageContaining("REQUESTED -> READY");

    assertThatThrownBy(() -> SandboxState.TERMINATED.requireTransitionTo(SandboxState.READY))
        .isInstanceOf(IllegalSandboxTransitionException.class);

    // Skipping teardown entirely: a live sandbox cannot jump to a clean terminal state without
    // passing through RELEASING, or a container could be left running behind a "TERMINATED" row.
    assertThatThrownBy(() -> SandboxState.READY.requireTransitionTo(SandboxState.TERMINATED))
        .isInstanceOf(IllegalSandboxTransitionException.class);

    assertThatThrownBy(() -> SandboxState.REQUESTED.requireTransitionTo(null))
        .isInstanceOf(IllegalSandboxTransitionException.class);
  }

  @Test
  void everyIllegalPairIsRejectedAndEveryLegalPairIsAccepted() {
    List<String> wrong = new ArrayList<>();
    for (SandboxState from : SandboxState.values()) {
      for (SandboxState to : SandboxState.values()) {
        boolean declared = from.allowedNext().contains(to);
        boolean thrown = false;
        try {
          from.requireTransitionTo(to);
        } catch (IllegalSandboxTransitionException expected) {
          thrown = true;
        }
        if (declared == thrown) {
          wrong.add(from + " -> " + to);
        }
      }
    }
    assertThat(wrong)
        .as("requireTransitionTo must agree with allowedNext for every pair")
        .isEmpty();
  }

  @Test
  void selfTransitionsAreNeverLegal() {
    for (SandboxState state : SandboxState.values()) {
      assertThat(state.canTransitionTo(state)).as("%s -> itself", state).isFalse();
    }
  }

  @Test
  void predecessorSetsAreTheInverseOfTheGraph() {
    assertThat(SandboxState.predecessorsOf(SandboxState.READY))
        .containsExactly(SandboxState.PROVISIONING);
    assertThat(SandboxState.predecessorsOf(SandboxState.RELEASING))
        .containsExactlyInAnyOrder(
            SandboxState.PROVISIONING, SandboxState.READY, SandboxState.CANCEL_REQUESTED);
    assertThat(SandboxState.predecessorsOf(SandboxState.TERMINATED))
        .containsExactly(SandboxState.RELEASING);
    assertThat(SandboxState.predecessorsOf(SandboxState.REQUESTED)).isEmpty();
  }

  @Test
  void everyNonTerminalStateCanReachATerminalStateWithoutRuntimeDispatch() {
    // Rollback invariant: turning off sandbox.runtime.dispatch.enabled must still allow
    // cancellation, cleanup and reconciliation. That is only possible if no non-terminal state is
    // a dead end.
    for (SandboxState state : SandboxState.values()) {
      if (state.terminal()) {
        continue;
      }
      assertThat(reachableTerminals(state)).as("terminals reachable from %s", state).isNotEmpty();
    }
  }

  @Test
  void leaseIsHeldExactlyInTheNonTerminalRuntimeOwnedStates() {
    assertThat(EnumSet.allOf(SandboxState.class).stream().filter(SandboxState::leaseHeld).toList())
        .containsExactlyInAnyOrder(
            SandboxState.PROVISIONING,
            SandboxState.READY,
            SandboxState.CANCEL_REQUESTED,
            SandboxState.RELEASING);
    assertThat(EnumSet.allOf(SandboxState.class).stream().filter(SandboxState::terminal).toList())
        .noneMatch(SandboxState::leaseHeld);
  }

  private static Set<SandboxState> reachableTerminals(SandboxState from) {
    Set<SandboxState> seen = EnumSet.of(from);
    Set<SandboxState> terminals = EnumSet.noneOf(SandboxState.class);
    List<SandboxState> queue = new ArrayList<>(List.of(from));
    while (!queue.isEmpty()) {
      SandboxState current = queue.removeFirst();
      for (SandboxState next : current.allowedNext()) {
        if (next.terminal()) {
          terminals.add(next);
        } else if (seen.add(next)) {
          queue.add(next);
        }
      }
    }
    return terminals;
  }
}
