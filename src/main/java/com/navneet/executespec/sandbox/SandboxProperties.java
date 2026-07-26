package com.navneet.executespec.sandbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/** Configuration for the sandbox lifecycle and the CONTAINER_STRICT_V1 runtime. */
@Data
@ConfigurationProperties(prefix = "executespec.sandbox")
public class SandboxProperties {

  /** Host directory every sandbox writable mount must live under. */
  private String workspaceRoot = "/var/lib/executespec/sandboxes";

  /** Default image for a strict sandbox. Pin by digest in any deployed configuration. */
  private String image = "docker.io/library/debian:stable-slim";

  /** Numeric non-root identity used for both container startup and every docker exec. */
  private int runtimeUid = 65532;

  /** Numeric non-root group used for both container startup and every docker exec. */
  private int runtimeGid = 65532;

  /** How long a runtime lease is valid before another JVM may take it over. */
  private Duration leaseDuration = Duration.ofMinutes(2);

  /** Default sandbox TTL. */
  private Duration timeToLive = Duration.ofMinutes(30);

  /** Wall-clock ceiling for the container itself. */
  private Duration wallClock = Duration.ofMinutes(30);

  private double cpus = 2.0;
  private long memoryBytes = 2L * 1024 * 1024 * 1024;
  private int pids = 256;
  private long tmpfsBytes = 256L * 1024 * 1024;

  /** Seconds a docker CLI invocation may take before it is abandoned. */
  private int commandTimeoutSeconds = 120;

  /** How many orphan and expiry rows a single reconciliation sweep settles. */
  private int reconcileBatchSize = 50;
}
