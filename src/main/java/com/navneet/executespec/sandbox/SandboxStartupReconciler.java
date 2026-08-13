package com.navneet.executespec.sandbox;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Settles sandboxes abandoned by a crashed JVM, and sandboxes past their TTL.
 *
 * <p>Runs once at startup and then on a schedule. Deliberately flag-free: a crash that happens
 * while the runtime is enabled must still be recoverable after the runtime is turned off, and a
 * node that is not allowed to start sandboxes is still allowed to clean up.
 *
 * <p>The owner identity is host plus a per-process suffix, so two JVMs on the same host cannot
 * mistake each other's leases for their own.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SandboxStartupReconciler {

  private final RunSandboxLifecycleService lifecycle;

  private final String owner = resolveOwner();

  @EventListener(ApplicationReadyEvent.class)
  public void reconcileOnStartup() {
    sweep("startup");
  }

  @Scheduled(fixedDelayString = "${executespec.sandbox.reconcile-interval-ms:60000}")
  public void reconcilePeriodically() {
    sweep("scheduled");
  }

  private void sweep(String trigger) {
    try {
      int orphans = lifecycle.reconcile(owner);
      int expired = lifecycle.expire();
      int workspaces = lifecycle.cleanupVerificationWorkspaces();
      if (orphans > 0 || expired > 0 || workspaces > 0) {
        log.info(
            "{} sandbox sweep settled {} orphans, {} expired sandboxes, and {} verification"
                + " workspaces",
            trigger,
            orphans,
            expired,
            workspaces);
      }
    } catch (RuntimeException failure) {
      // A sweep failure must not take down the scheduler thread, and must never be silent.
      log.error("{} sandbox sweep failed", trigger, failure);
    }
  }

  private static String resolveOwner() {
    String host;
    try {
      host = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException unresolved) {
      host = "unknown-host";
    }
    return host + ":" + ProcessHandle.current().pid() + ":" + UUID.randomUUID();
  }
}
