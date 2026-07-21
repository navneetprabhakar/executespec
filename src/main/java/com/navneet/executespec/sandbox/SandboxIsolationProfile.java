package com.navneet.executespec.sandbox;

/**
 * Isolation profiles a sandbox may be admitted under. Persisted alongside {@code
 * isolation_profile_version} so a later hardening change is visible in historical rows rather than
 * retroactively implied.
 */
public enum SandboxIsolationProfile {

  /** No runtime. The dispatcher resolves to the no-op runtime and nothing executes. */
  NOT_ENABLED(1),

  /**
   * Hardened container: non-root, read-only root filesystem, explicit writable mounts only, all
   * capabilities dropped, no host Docker socket, no host credential mounts, network denied, and
   * CPU/memory/PID/time limits. Shipped dark; see {@link ContainerStrictV1Runtime} for the
   * properties that are asserted versus the properties that remain unproven.
   */
  CONTAINER_STRICT_V1(1);

  private final int version;

  SandboxIsolationProfile(int version) {
    this.version = version;
  }

  public int version() {
    return version;
  }
}
