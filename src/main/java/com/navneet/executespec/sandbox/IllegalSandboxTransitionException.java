package com.navneet.executespec.sandbox;

/** Thrown when a caller asks for a transition the lifecycle state machine does not define. */
public class IllegalSandboxTransitionException extends RuntimeException {

  private final transient SandboxState from;
  private final transient SandboxState to;

  public IllegalSandboxTransitionException(SandboxState from, SandboxState to) {
    super("illegal sandbox transition %s -> %s".formatted(from, to));
    this.from = from;
    this.to = to;
  }

  public SandboxState from() {
    return from;
  }

  public SandboxState to() {
    return to;
  }
}
