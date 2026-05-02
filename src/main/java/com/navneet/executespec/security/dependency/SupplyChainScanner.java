package com.navneet.executespec.security.dependency;

import java.util.List;

/**
 * 2026-05-02 — vendor-neutral interface for supply-chain dependency scanners (Socket.dev, Snyk,
 * Phylum, etc.). Today this is a scaffold — {@link NoopSupplyChainScanner} is the default
 * binding so dep installs don't break in environments that haven't wired in a real scanner.
 *
 * <p>An org wires in their preferred vendor by:
 *
 * <ol>
 *   <li>Implementing this interface as a Spring {@code @Component} marked
 *       {@code @ConditionalOnProperty(prefix = "agent.security.deps.scanner", name = "vendor")}.
 *   <li>Setting {@code agent.security.deps.scanner.vendor=socket} (or whatever name they wire)
 *       in application.yml. The default {@link NoopSupplyChainScanner} drops out via
 *       {@code @ConditionalOnMissingBean}.
 *   <li>Providing API credentials via env / Secret Manager — the implementation's responsibility,
 *       not surfaced on this interface.
 * </ol>
 *
 * <p>Why an interface and not a direct Socket.dev integration? Three reasons: (a) Socket.dev /
 * Snyk / Phylum each have wildly different APIs and pricing models — coupling to one locks the
 * other out, (b) some orgs run scanners on-prem (Sonatype IQ, Anchore) and the abstraction lets
 * us point at any of them, (c) vendor selection is a procurement decision that shouldn't gate
 * the rest of the security work shipping today.
 *
 * <p>The scanner is invoked at install time — after {@link DependencySecurityPolicy} validates
 * the install command shape but before the install executes (so we can refuse-to-install when a
 * critical advisory hits). Wiring is in {@code SequentialExecutorV2} (TODO follow-up — gated on
 * {@link #isEnabled()}).
 */
public interface SupplyChainScanner {

  /** Whether this scanner is configured + ready. {@code false} short-circuits every scan. */
  boolean isEnabled();

  /**
   * Scan a list of {@code (name, version)} package coordinates and return any advisories found.
   * Implementation MUST be best-effort: a transient API failure should return an empty list +
   * log, not throw — we never want a flaky scanner to take down the pipeline.
   */
  List<Advisory> scan(List<PackageCoordinate> packages);

  /** Single package + pinned version, the input shape every scanner accepts. */
  record PackageCoordinate(String ecosystem, String name, String version) {}

  /** A single finding from the scanner. */
  record Advisory(
      String packageName,
      String version,
      Severity severity,
      String summary,
      String referenceUrl) {}

  enum Severity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
  }
}
