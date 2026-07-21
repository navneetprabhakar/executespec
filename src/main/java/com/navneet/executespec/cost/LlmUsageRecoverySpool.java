package com.navneet.executespec.cost;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.observability.Metrics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Secret-free local WAL for a terminal usage fact that could not reach PostgreSQL after a provider
 * response. The write-ahead intent remains the cross-JVM source of missing-terminal truth; this
 * spool preserves the exact numeric terminal payload across an application restart and replays it
 * idempotently by the deterministic physical-attempt id.
 */
@Component
@Slf4j
public class LlmUsageRecoverySpool {

  private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> FILE_PERMISSIONS =
      PosixFilePermissions.fromString("rw-------");

  private final LlmUsageTerminalOutbox terminalOutbox;
  private final ObjectMapper objectMapper;
  private final Metrics metrics;
  private final Path directory;

  public LlmUsageRecoverySpool(
      LlmUsageTerminalOutbox terminalOutbox,
      ObjectMapper objectMapper,
      Metrics metrics,
      @Value("${executespec.usage-recovery-dir:${agent.workspace:workspace}/usage-recovery}")
          String directory) {
    this.terminalOutbox = terminalOutbox;
    this.objectMapper = objectMapper;
    this.metrics = metrics;
    this.directory = Path.of(directory).toAbsolutePath().normalize();
  }

  /** Atomically persists one bounded, already-redacted terminal fact for later replay. */
  public boolean enqueue(RunStepUsage row) {
    if (row == null || row.getId() == null || row.getId().isBlank()) return false;
    try {
      Files.createDirectories(directory);
      setPermissions(directory, DIRECTORY_PERMISSIONS);
      String basename =
          UUID.nameUUIDFromBytes(row.getId().getBytes(StandardCharsets.UTF_8)).toString();
      Path target = directory.resolve(basename + ".json");
      Path temporary = Files.createTempFile(directory, basename + "-", ".tmp");
      try {
        setPermissions(temporary, FILE_PERMISSIONS);
        objectMapper.writeValue(temporary.toFile(), row);
        moveAtomically(temporary, target);
        setPermissions(target, FILE_PERMISSIONS);
      } finally {
        Files.deleteIfExists(temporary);
      }
      metrics.recordLlmUsagePersistence(row.getAttemptStatus(), "spooled");
      log.error(
          "Canonical LLM terminal usage spooled for recovery attemptId={} artifact={}",
          row.getPhysicalAttemptId(),
          target.getFileName());
      recordSpoolState();
      return true;
    } catch (Exception spoolFailure) {
      metrics.recordLlmUsagePersistence(row.getAttemptStatus(), "spool_failed");
      log.error(
          "Canonical LLM terminal usage recovery spool FAILED attemptId={} errorClass={}",
          row.getPhysicalAttemptId(),
          spoolFailure.getClass().getSimpleName());
      return false;
    }
  }

  @Scheduled(
      fixedDelayString = "${executespec.usage-recovery-interval-millis:30000}",
      initialDelayString = "${executespec.usage-recovery-initial-delay-millis:30000}")
  public void replayPending() {
    if (!Files.isDirectory(directory)) return;
    try (Stream<Path> files = Files.list(directory)) {
      files
          .filter(path -> path.getFileName().toString().endsWith(".json"))
          .sorted(Comparator.comparing(Path::toString))
          .forEach(this::replayOne);
      recordSpoolState();
    } catch (IOException listingFailure) {
      metrics.recordLlmUsagePersistence("RECOVERY", "list_failed");
      log.error(
          "Unable to list LLM usage recovery spool errorClass={}",
          listingFailure.getClass().getSimpleName());
    }
  }

  private void replayOne(Path path) {
    try {
      RunStepUsage row = objectMapper.readValue(path.toFile(), RunStepUsage.class);
      terminalOutbox.persist(row);
      Files.delete(path);
      metrics.recordLlmUsagePersistence(row.getAttemptStatus(), "replayed");
      log.info("Replayed canonical LLM terminal usage attemptId={}", row.getPhysicalAttemptId());
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalidPayload) {
      deadLetter(path, invalidPayload);
    } catch (Exception replayFailure) {
      metrics.recordLlmUsagePersistence("RECOVERY", "retry_failed");
      log.warn(
          "LLM usage recovery replay remains pending file={}: {}",
          path.getFileName(),
          replayFailure.getClass().getSimpleName());
    }
  }

  private void deadLetter(Path path, Exception failure) {
    try {
      Path dead =
          path.resolveSibling(path.getFileName().toString().replaceFirst("\\.json$", ".dead"));
      moveAtomically(path, dead);
      setPermissions(dead, FILE_PERMISSIONS);
      metrics.recordLlmUsagePersistence("RECOVERY", "dead_letter");
      log.error(
          "LLM usage local recovery payload dead-lettered file={} errorClass={}",
          dead.getFileName(),
          failure.getClass().getSimpleName());
    } catch (IOException moveFailure) {
      metrics.recordLlmUsagePersistence("RECOVERY", "dead_letter_failed");
      log.error(
          "Unable to dead-letter LLM usage local recovery payload errorClass={}",
          moveFailure.getClass().getSimpleName());
    }
  }

  private void recordSpoolState() {
    if (!Files.isDirectory(directory)) {
      metrics.recordLlmUsageLocalRecovery(0, 0, 0);
      return;
    }
    try (Stream<Path> paths = Files.list(directory)) {
      var files = paths.toList();
      long pending = files.stream().filter(path -> path.toString().endsWith(".json")).count();
      long dead = files.stream().filter(path -> path.toString().endsWith(".dead")).count();
      long oldestEpochMillis =
          files.stream()
              .filter(path -> path.toString().endsWith(".json"))
              .mapToLong(
                  path -> {
                    try {
                      return Files.getLastModifiedTime(path).toMillis();
                    } catch (IOException ignored) {
                      return System.currentTimeMillis();
                    }
                  })
              .min()
              .orElse(System.currentTimeMillis());
      long oldestSeconds =
          pending == 0 ? 0 : Math.max(0, Instant.now().getEpochSecond() - oldestEpochMillis / 1000);
      metrics.recordLlmUsageLocalRecovery(pending, dead, oldestSeconds);
    } catch (IOException ignored) {
      metrics.recordLlmUsagePersistence("RECOVERY", "local_state_failed");
    }
  }

  private static void setPermissions(Path path, Set<PosixFilePermission> permissions)
      throws IOException {
    try {
      Files.setPosixFilePermissions(path, permissions);
    } catch (UnsupportedOperationException ignored) {
      // Windows/non-POSIX filesystems do not expose mode bits; platform ACLs remain authoritative.
    }
  }

  private static void moveAtomically(Path source, Path target) throws IOException {
    try {
      Files.move(
          source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException ignored) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
