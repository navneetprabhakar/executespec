package com.navneet.executespec.sandbox;

import java.time.Duration;
import java.util.List;

/**
 * One command to run inside an already-provisioned sandbox.
 *
 * <p>An <strong>argument vector</strong>, never a shell string. This is the whole point of the
 * type: customer-generated build and test commands are the input, so there must be no layer at
 * which a repository-controlled string is handed to {@code /bin/sh}. If a caller genuinely needs
 * shell semantics it must say so explicitly by passing {@code ["sh", "-c", script]} — which is then
 * visible in the argv, in the audit trail and in review, rather than being an invisible default.
 *
 * <p>Validation here is deliberately structural, not semantic. It rejects vectors that could be
 * misparsed by the container CLI or that would blow a bound; it makes no attempt to decide whether
 * a command is "safe", because that judgement is exactly what the container isolation exists to
 * avoid having to make.
 *
 * @param argv the executable and its arguments; {@code argv[0]} is the program
 * @param workdir absolute directory inside the sandbox, or null to use the container's own workdir
 * @param timeout wall-clock ceiling enforced by the caller-side runtime; see {@link
 *     SandboxExecResult} for what a timeout does and does not guarantee
 */
public record SandboxExecRequest(List<String> argv, String workdir, Duration timeout) {

  /**
   * The longest any single sandbox command may be asked to run.
   *
   * <p>A hard constant rather than configuration on purpose: this is a containment bound, and a
   * containment bound that an operator can raise to "unlimited" through a config file is not a
   * bound. It matches the default container wall-clock, beyond which the container itself is gone
   * and the exec would be pointless.
   */
  public static final Duration MAX_TIMEOUT = Duration.ofMinutes(30);

  private static final int MAX_ARGS = 512;
  private static final int MAX_ARG_CHARS = 8192;

  public SandboxExecRequest {
    if (argv == null || argv.isEmpty()) {
      throw new IllegalArgumentException("sandbox exec requires a non-empty argument vector");
    }
    if (argv.size() > MAX_ARGS) {
      throw new IllegalArgumentException("sandbox exec argument vector exceeds " + MAX_ARGS);
    }
    for (String argument : argv) {
      requireUsableArgument(argument);
    }
    // The container CLI takes the container name positionally and the command after it. A first
    // argument that looks like an option is refused rather than relied upon to be parsed
    // positionally, so the contract does not depend on the CLI's option-interspersal behaviour.
    if (argv.getFirst().startsWith("-")) {
      throw new IllegalArgumentException(
          "sandbox exec program must not start with '-': " + argv.getFirst());
    }
    if (workdir != null && (!workdir.startsWith("/") || workdir.contains(".."))) {
      throw new IllegalArgumentException(
          "sandbox exec workdir must be absolute and free of traversal: " + workdir);
    }
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("sandbox exec requires a positive timeout");
    }
    if (timeout.compareTo(MAX_TIMEOUT) > 0) {
      throw new IllegalArgumentException(
          "sandbox exec timeout %s exceeds the %s ceiling".formatted(timeout, MAX_TIMEOUT));
    }
    argv = List.copyOf(argv);
  }

  /** Convenience for a command that runs in the container's own working directory. */
  public static SandboxExecRequest of(List<String> argv, Duration timeout) {
    return new SandboxExecRequest(argv, null, timeout);
  }

  /** A short, non-secret label for logs and audit. Never the full vector. */
  public String programLabel() {
    return argv.getFirst();
  }

  private static void requireUsableArgument(String argument) {
    if (argument == null) {
      throw new IllegalArgumentException("sandbox exec arguments must not be null");
    }
    if (argument.length() > MAX_ARG_CHARS) {
      throw new IllegalArgumentException("sandbox exec argument exceeds " + MAX_ARG_CHARS);
    }
    // A NUL terminates the argument at the syscall boundary, so an argument containing one does
    // not mean at the exec what it means in Java. Refuse rather than silently truncate.
    if (argument.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("sandbox exec arguments must not contain NUL");
    }
  }
}
