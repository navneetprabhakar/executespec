package com.navneet.executespec.sandbox;

/** Network egress policy recorded for a sandbox. */
public enum SandboxNetworkPolicy {
  /** The only implemented policy: the container gets no network namespace access. */
  DENY_ALL,

  /**
   * Reserved. Egress would be permitted only through a controlled allowlisting proxy. No such proxy
   * exists yet, so admitting a sandbox under this policy is rejected.
   */
  ALLOWLIST_PROXY
}
