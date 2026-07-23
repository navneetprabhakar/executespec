package com.navneet.executespec.sandbox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * The exact resolved runtime configuration for one sandbox. Digested into {@code
 * run_sandbox.config_digest} so "what was actually enforced" is auditable rather than inferred from
 * whatever the code happens to do today.
 *
 * @param image container image reference, pinned by digest where the caller supplies one
 * @param workdir working directory inside the sandbox
 * @param writableMounts the only paths the sandbox may write to; everything else is read-only
 * @param limits CPU, memory, PID and wall-clock ceilings
 * @param networkPolicy egress policy; only {@link SandboxNetworkPolicy#DENY_ALL} is implemented
 * @param secretPolicy secret-exposure policy
 */
public record SandboxRuntimeSpec(
    String image,
    String workdir,
    List<WritableMount> writableMounts,
    SandboxRuntimeLimits limits,
    SandboxNetworkPolicy networkPolicy,
    SandboxSecretPolicy secretPolicy) {

  /** A host directory the sandbox may write to, bound at an explicit container path. */
  public record WritableMount(String hostPath, String containerPath) {}

  public SandboxRuntimeSpec {
    if (image == null || image.isBlank()) {
      throw new IllegalArgumentException("sandbox image is required");
    }
    if (workdir == null || !workdir.startsWith("/")) {
      throw new IllegalArgumentException("sandbox workdir must be an absolute path");
    }
    writableMounts = List.copyOf(writableMounts == null ? List.of() : writableMounts);
    if (networkPolicy != SandboxNetworkPolicy.DENY_ALL) {
      throw new IllegalArgumentException(
          "only DENY_ALL is implemented; no controlled egress proxy exists yet");
    }
    if (secretPolicy != SandboxSecretPolicy.NO_HOST_SECRETS) {
      throw new IllegalArgumentException("only NO_HOST_SECRETS is implemented");
    }
  }

  /**
   * Stable digest over every field that changes what is enforced. Deliberately excludes host paths
   * that vary per run so the same policy yields the same digest across sandboxes.
   */
  public String configDigest() {
    StringBuilder canonical =
        new StringBuilder()
            .append("image=")
            .append(image)
            .append(";workdir=")
            .append(workdir)
            .append(";network=")
            .append(networkPolicy)
            .append(";secrets=")
            .append(secretPolicy)
            .append(";limits=")
            .append(limits.canonical());
    writableMounts.stream()
        .map(WritableMount::containerPath)
        .sorted()
        .forEach(path -> canonical.append(";w=").append(path));
    return "sha256:" + sha256(canonical.toString());
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  /** Resource ceilings. Every one of these is mandatory; there is no "unlimited" option. */
  public record SandboxRuntimeLimits(
      double cpus, long memoryBytes, int pids, Duration wallClock, long tmpfsBytes) {

    public SandboxRuntimeLimits {
      require(cpus > 0, "cpus must be positive");
      require(memoryBytes > 0, "memoryBytes must be positive");
      require(pids > 0, "pids must be positive");
      require(
          wallClock != null && !wallClock.isNegative() && !wallClock.isZero(),
          "wallClock must be positive");
      require(tmpfsBytes > 0, "tmpfsBytes must be positive");
    }

    String canonical() {
      return "cpus=%s,mem=%d,pids=%d,wall=%ds,tmpfs=%d"
          .formatted(cpus, memoryBytes, pids, wallClock.toSeconds(), tmpfsBytes);
    }

    private static void require(boolean condition, String message) {
      if (!condition) {
        throw new IllegalArgumentException(message);
      }
    }
  }
}
