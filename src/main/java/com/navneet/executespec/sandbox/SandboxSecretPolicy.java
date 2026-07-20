package com.navneet.executespec.sandbox;

/** Secret-exposure policy recorded for a sandbox. */
public enum SandboxSecretPolicy {
  /**
   * The only implemented policy: no host credential file, credential helper, agent socket or
   * ambient cloud-metadata credential is mounted or forwarded into the sandbox.
   */
  NO_HOST_SECRETS
}
