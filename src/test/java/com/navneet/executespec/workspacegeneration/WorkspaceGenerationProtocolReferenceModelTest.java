package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Executable v1 reference model for the coupled BUILD terminal protocol.
 *
 * <p>Stable invariant identifiers are shared with the remediation ledger. The model is deliberately
 * provider/filesystem free: it explores durable command ordering and rejects control-flow-derived
 * outcomes.
 */
class WorkspaceGenerationProtocolReferenceModelTest {

  private static final String I_TERMINAL_OWNER =
      "GENERATION_TERMINAL_STATE_OWNS_TASK_AND_OPERATION";
  private static final String I_ONE_ATTEMPT = "ONE_UNRESOLVED_ATTEMPT_PER_RUN";
  private static final String I_PUBLICATION = "PUBLICATION_REQUIRES_EXACT_ACCEPTED_FRONTIER";
  private static final String I_CANCELLATION = "CANCELLATION_CANNOT_BE_OVERWRITTEN";

  @Test
  void allReachableProtocolStatesSatisfyHardInvariantsAndCanConverge() {
    State initial = State.initial();
    Map<State, Map<Command, State>> graph = explore(initial);

    assertThat(graph).hasSizeGreaterThan(20);
    graph.keySet().forEach(this::assertInvariants);
    graph.keySet().stream()
        .filter(State::supportedNonTerminal)
        .forEach(
            state ->
                assertThat(canReachTerminal(state, graph, new HashSet<>()))
                    .as("every supported state has a bounded convergence path: %s", state)
                    .isTrue());
  }

  @Test
  void acceptedAndRolledBackStatesRejectOppositeTerminalCommands() {
    Map<State, Map<Command, State>> graph = explore(State.initial());

    graph.keySet().stream()
        .filter(state -> state.generation() == Generation.ACCEPTED)
        .forEach(state -> assertThat(apply(state, Command.BEGIN_ROLLBACK)).isNull());
    graph.keySet().stream()
        .filter(state -> state.generation() == Generation.ROLLED_BACK)
        .forEach(state -> assertThat(apply(state, Command.ACCEPT)).isNull());
  }

  private Map<State, Map<Command, State>> explore(State initial) {
    Map<State, Map<Command, State>> graph = new HashMap<>();
    ArrayDeque<State> pending = new ArrayDeque<>();
    pending.add(initial);
    while (!pending.isEmpty()) {
      State state = pending.removeFirst();
      if (graph.containsKey(state)) continue;
      Map<Command, State> edges = new EnumMap<>(Command.class);
      for (Command command : Command.values()) {
        State next = apply(state, command);
        if (next != null && !next.equals(state)) {
          edges.put(command, next);
          pending.addLast(next);
        }
      }
      graph.put(state, edges);
    }
    return graph;
  }

  private void assertInvariants(State state) {
    assertThat(state.unresolvedAttempts()).as(I_ONE_ATTEMPT).isBetween(0, 1);
    if (state.task() == Task.COMPLETE || state.operation() == Operation.SUCCEEDED) {
      assertThat(state.generation()).as(I_TERMINAL_OWNER).isEqualTo(Generation.ACCEPTED);
      assertThat(state.frontierBound()).as(I_PUBLICATION).isTrue();
    }
    if (state.generation() == Generation.ROLLED_BACK) {
      assertThat(state.operation()).as(I_TERMINAL_OWNER).isNotEqualTo(Operation.SUCCEEDED);
      assertThat(state.frontierBound()).as(I_PUBLICATION).isFalse();
    }
    if (state.run() == Run.CANCELLED) {
      assertThat(state.task()).as(I_CANCELLATION).isEqualTo(Task.CANCELLED);
    }
    if (state.frontierBound()) {
      assertThat(state.generation()).as(I_PUBLICATION).isEqualTo(Generation.ACCEPTED);
      assertThat(state.checkpointComplete()).as(I_PUBLICATION).isTrue();
    }
  }

  private boolean canReachTerminal(
      State state, Map<State, Map<Command, State>> graph, Set<State> visiting) {
    if (state.generation().terminal()) return true;
    if (!visiting.add(state)) return false;
    boolean reachable =
        graph.getOrDefault(state, Map.of()).values().stream()
            .anyMatch(next -> canReachTerminal(next, graph, new HashSet<>(visiting)));
    return reachable;
  }

  private State apply(State state, Command command) {
    return switch (command) {
      case ADMIT ->
          state.generation() == Generation.NONE && state.run() == Run.WORKING
              ? state.with(
                  Generation.PREPARED,
                  Operation.RUNNING,
                  Authority.BUILD,
                  Intent.NONE,
                  false,
                  false,
                  1)
              : null;
      case MUTATE -> state.advance(Generation.PREPARED, Generation.MUTATING);
      case VERIFY -> state.advance(Generation.MUTATING, Generation.VERIFYING);
      case VERIFY_PASS -> state.advance(Generation.VERIFYING, Generation.VERIFIED);
      case BEGIN_CHECKPOINT -> state.advance(Generation.VERIFIED, Generation.CHECKPOINTING);
      case COMPLETE_CHECKPOINT ->
          state.generation() == Generation.CHECKPOINTING && !state.checkpointComplete()
              ? state.withCheckpoint()
              : null;
      case ACCEPT ->
          state.generation() == Generation.CHECKPOINTING
                  && state.checkpointComplete()
                  && state.run() == Run.WORKING
              ? state.with(
                  Generation.ACCEPTED,
                  state.operation(),
                  state.authority(),
                  Intent.MARK_COMPLETE,
                  true,
                  true,
                  0)
              : null;
      case BEGIN_ROLLBACK ->
          state.generation().mutable()
              ? state.with(
                  Generation.ROLLING_BACK,
                  state.operation(),
                  state.authority(),
                  Intent.NONE,
                  state.checkpointComplete(),
                  false,
                  1)
              : null;
      case FINISH_ROLLBACK ->
          state.generation() == Generation.ROLLING_BACK
              ? state.with(
                  Generation.ROLLED_BACK,
                  state.operation(),
                  state.authority(),
                  Intent.MARK_PARTIAL,
                  state.checkpointComplete(),
                  false,
                  0)
              : null;
      case PUBLISH_TASK -> publishTask(state);
      case PUBLISH_OPERATION -> publishOperation(state);
      case RELEASE_AUTHORITY ->
          state.authority() != Authority.NONE ? state.withAuthority(Authority.NONE) : null;
      case RECOVERY_TAKEOVER ->
          state.generation() == Generation.ROLLING_BACK && state.authority() == Authority.NONE
              ? state.withAuthority(Authority.RECOVERY)
              : null;
      case CANCEL ->
          state.run() == Run.WORKING ? state.withRunAndTask(Run.CANCELLED, Task.CANCELLED) : null;
      case DUPLICATE_ADMISSION -> null;
    };
  }

  private State publishTask(State state) {
    if (state.task() == Task.CANCELLED) return null;
    if (state.generation() == Generation.ACCEPTED
        && state.frontierBound()
        && state.intent() == Intent.MARK_COMPLETE) {
      return state.withTask(Task.COMPLETE);
    }
    if (state.generation() == Generation.ROLLED_BACK && state.intent() == Intent.MARK_PARTIAL) {
      return state.withTask(Task.PARTIAL);
    }
    return null;
  }

  private State publishOperation(State state) {
    if (state.operation() != Operation.RUNNING) return null;
    if (state.generation() == Generation.ACCEPTED && state.frontierBound()) {
      return state.withOperation(Operation.SUCCEEDED);
    }
    if (state.generation() == Generation.ROLLED_BACK) {
      return state.withOperation(Operation.FAILED);
    }
    return null;
  }

  private enum Command {
    ADMIT,
    MUTATE,
    VERIFY,
    VERIFY_PASS,
    BEGIN_CHECKPOINT,
    COMPLETE_CHECKPOINT,
    ACCEPT,
    BEGIN_ROLLBACK,
    FINISH_ROLLBACK,
    PUBLISH_TASK,
    PUBLISH_OPERATION,
    RELEASE_AUTHORITY,
    RECOVERY_TAKEOVER,
    CANCEL,
    DUPLICATE_ADMISSION
  }

  private enum Run {
    WORKING,
    CANCELLED
  }

  private enum Task {
    WORKING,
    COMPLETE,
    PARTIAL,
    CANCELLED
  }

  private enum Generation {
    NONE,
    PREPARED,
    MUTATING,
    VERIFYING,
    VERIFIED,
    CHECKPOINTING,
    ACCEPTED,
    ROLLING_BACK,
    ROLLED_BACK;

    boolean mutable() {
      return this == PREPARED
          || this == MUTATING
          || this == VERIFYING
          || this == VERIFIED
          || this == CHECKPOINTING;
    }

    boolean terminal() {
      return this == NONE || this == ACCEPTED || this == ROLLED_BACK;
    }
  }

  private enum Operation {
    NONE,
    RUNNING,
    SUCCEEDED,
    FAILED
  }

  private enum Authority {
    NONE,
    BUILD,
    RECOVERY
  }

  private enum Intent {
    NONE,
    MARK_COMPLETE,
    MARK_PARTIAL
  }

  private record State(
      Run run,
      Task task,
      Generation generation,
      Operation operation,
      Authority authority,
      Intent intent,
      boolean checkpointComplete,
      boolean frontierBound,
      int unresolvedAttempts) {

    static State initial() {
      return new State(
          Run.WORKING,
          Task.WORKING,
          Generation.NONE,
          Operation.NONE,
          Authority.NONE,
          Intent.NONE,
          false,
          false,
          0);
    }

    boolean supportedNonTerminal() {
      return generation != Generation.NONE && !generation.terminal();
    }

    State advance(Generation expected, Generation next) {
      return generation == expected
          ? with(
              next,
              operation,
              authority,
              intent,
              checkpointComplete,
              frontierBound,
              unresolvedAttempts)
          : null;
    }

    State with(
        Generation nextGeneration,
        Operation nextOperation,
        Authority nextAuthority,
        Intent nextIntent,
        boolean nextCheckpoint,
        boolean nextFrontier,
        int nextUnresolved) {
      return new State(
          run,
          task,
          nextGeneration,
          nextOperation,
          nextAuthority,
          nextIntent,
          nextCheckpoint,
          nextFrontier,
          nextUnresolved);
    }

    State withCheckpoint() {
      return new State(
          run,
          task,
          generation,
          operation,
          authority,
          intent,
          true,
          frontierBound,
          unresolvedAttempts);
    }

    State withAuthority(Authority next) {
      return new State(
          run,
          task,
          generation,
          operation,
          next,
          intent,
          checkpointComplete,
          frontierBound,
          unresolvedAttempts);
    }

    State withRunAndTask(Run nextRun, Task nextTask) {
      return new State(
          nextRun,
          nextTask,
          generation,
          operation,
          authority,
          intent,
          checkpointComplete,
          frontierBound,
          unresolvedAttempts);
    }

    State withTask(Task next) {
      return withRunAndTask(run, next);
    }

    State withOperation(Operation next) {
      return new State(
          run,
          task,
          generation,
          next,
          authority,
          intent,
          checkpointComplete,
          frontierBound,
          unresolvedAttempts);
    }
  }
}
