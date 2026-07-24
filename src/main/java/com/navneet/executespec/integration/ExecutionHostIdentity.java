package com.navneet.executespec.integration;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import lombok.extern.slf4j.Slf4j;

/** Stable host identity for execution/recovery leases across JVM process restarts. */
@Slf4j
public final class ExecutionHostIdentity {

  private static final int MAX_LENGTH = 128;

  private ExecutionHostIdentity() {}

  public static String current() {
    return resolve(
        System.getenv("EXECUTESPEC_EXECUTION_HOST_ID"),
        System.getenv("HOSTNAME"),
        readMachineId(),
        localHostname(),
        java.lang.management.ManagementFactory.getRuntimeMXBean().getName());
  }

  static String resolve(
      String configured,
      String hostnameEnvironment,
      String machineId,
      String networkHostname,
      String runtimeName) {
    String identity = firstNonBlank(configured, hostnameEnvironment);
    if (identity == null && !isBlank(machineId)) identity = "machine:" + machineId.trim();
    if (identity == null) identity = firstNonBlank(networkHostname, runtimeHost(runtimeName));
    if (identity == null) identity = runtimeName;
    identity = isBlank(identity) ? "unknown-host" : identity.trim();
    return identity.length() <= MAX_LENGTH ? identity : identity.substring(0, MAX_LENGTH);
  }

  private static String readMachineId() {
    try {
      Path path = Path.of("/etc/machine-id");
      return Files.isRegularFile(path) ? Files.readString(path) : null;
    } catch (Exception e) {
      log.debug("Execution host machine-id unavailable", e);
      return null;
    }
  }

  private static String localHostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      log.debug("Execution host network name unavailable", e);
      return null;
    }
  }

  private static String runtimeHost(String runtimeName) {
    if (isBlank(runtimeName)) return null;
    int separator = runtimeName.indexOf('@');
    return separator >= 0 && separator + 1 < runtimeName.length()
        ? runtimeName.substring(separator + 1)
        : null;
  }

  private static String firstNonBlank(String first, String second) {
    if (!isBlank(first)) return first;
    return isBlank(second) ? null : second;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
