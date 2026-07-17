package com.navneet.executespec.cost;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
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

  private final RunStepUsageRepository repository;
  private final ObjectMapper objectMapper;
  private final Metrics metrics;
  private final Path directory;

  public LlmUsageRecoverySpool(
      RunStepUsageRepository repository,
      ObjectMapper objectMapper,
      Metrics metrics,
      @Value("${executespec.usage-recovery-dir:${agent.workspace:workspace}/usage-recovery}")
          String directory) {
    this.repository = repository;
    this.objectMapper = objectMapper;
    this.metrics = metrics;
    this.directory = Path.of(directory).toAbsolutePath().normalize();
  }

  /** Atomically persists one bounded, already-redacted terminal fact for later replay. */
  public boolean enqueue(RunStepUsage row) {
    if (row == null || row.getId() == null || row.getId().isBlank()) return false;
    try {
      Files.createDirectories(directory);
      String basename =
          UUID.nameUUIDFromBytes(row.getId().getBytes(StandardCharsets.UTF_8)).toString();
      Path target = directory.resolve(basename + ".json");
      Path temporary = Files.createTempFile(directory, basename + "-", ".tmp");
      try {
        objectMapper.writeValue(temporary.toFile(), row);
        moveAtomically(temporary, target);
      } finally {
        Files.deleteIfExists(temporary);
      }
      metrics.recordLlmUsagePersistence(row.getAttemptStatus(), "spooled");
      log.error(
          "Canonical LLM terminal usage spooled for recovery attemptId={} path={}",
          row.getPhysicalAttemptId(),
          target);
      return true;
    } catch (Exception spoolFailure) {
      metrics.recordLlmUsagePersistence(row.getAttemptStatus(), "spool_failed");
      log.error(
          "Canonical LLM terminal usage recovery spool FAILED attemptId={}",
          row.getPhysicalAttemptId(),
          spoolFailure);
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
    } catch (IOException listingFailure) {
      metrics.recordLlmUsagePersistence("RECOVERY", "list_failed");
      log.error("Unable to list LLM usage recovery spool {}", directory, listingFailure);
    }
  }

  private void replayOne(Path path) {
    try {
      RunStepUsage row = objectMapper.readValue(path.toFile(), RunStepUsage.class);
      repository.save(row);
      Files.delete(path);
      metrics.recordLlmUsagePersistence(row.getAttemptStatus(), "replayed");
      log.info("Replayed canonical LLM terminal usage attemptId={}", row.getPhysicalAttemptId());
    } catch (Exception replayFailure) {
      metrics.recordLlmUsagePersistence("RECOVERY", "retry_failed");
      log.warn(
          "LLM usage recovery replay remains pending file={}: {}",
          path.getFileName(),
          replayFailure.getClass().getSimpleName());
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
