package com.navneet.executespec.sandbox;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Settles orphaned command intents conservatively; it never infers a successful command. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SandboxCommandRecoveryService {

  private final SandboxCommandJournalStore journal;

  @EventListener(ApplicationReadyEvent.class)
  public void recoverAtStartup() {
    recover();
  }

  @Scheduled(fixedDelayString = "${sandbox.command-recovery-delay-ms:60000}")
  public void recoverScheduled() {
    recover();
  }

  private void recover() {
    try {
      SandboxCommandJournalStore.RecoveryResult recovered = journal.recoverStaleCommands(100);
      if (recovered.total() > 0) {
        log.warn(
            "Recovered {} stale verification command(s): {} pre-dispatch UNAVAILABLE, {} dispatched"
                + " INDETERMINATE",
            recovered.total(),
            recovered.admittedUnavailable(),
            recovered.dispatchedIndeterminate());
      }
    } catch (RuntimeException failure) {
      log.error("Verification command recovery sweep failed", failure);
    }
  }
}
