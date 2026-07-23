package com.navneet.executespec.benchmark;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.navneet.executespec.security.GeneratedSecretScanner;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Operator-profile append-only run trajectory store for local, dev, and benchmark evaluation.
 *
 * <p>The normal customer SSE replay remains capped. Benchmark evidence instead lands in a
 * restart-safe NDJSON artifact, with every value redacted before the first byte is written. A
 * per-file OS lock serializes multiple JVMs sharing the benchmark volume. Exceeding the configured
 * cap marks the artifact truncated; exports then fail closed rather than claiming completeness.
 */
@Component
@Profile({"local", "dev", "benchmark"})
@Slf4j
public class BenchmarkTrajectoryStore {

  private static final Pattern SAFE_RUN_ID = Pattern.compile("[A-Za-z0-9._-]{1,160}");
  private static final Set<String> SENSITIVE_FIELDS =
      Set.of(
          "authorization",
          "proxyauthorization",
          "apikey",
          "accesskey",
          "secretkey",
          "accesstoken",
          "refreshtoken",
          "idtoken",
          "password",
          "passwd",
          "secret",
          "credential",
          "credentials",
          "cookie",
          "setcookie",
          "privatekey",
          "token",
          "bearertoken",
          "authtoken",
          "clientsecret",
          "pat",
          "personalaccesstoken");
  private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
  private static final Pattern ASSIGNED_SECRET =
      Pattern.compile(
          "(?i)\\b(api[_-]?key|(?:personal[_-]?access|access|refresh|auth)?[_-]?token|pat|password|passwd|secret|credential)\\s*([:=])\\s*([^\\s,;&}\\]]+)");
  private static final Pattern SENSITIVE_PATH =
      Pattern.compile(
          "(?i)(?:~|/)[^\\s\"']*(?:\\.env|creds?|credentials?|key[_-]?rotation|secrets?)[^\\s\"']*");

  private final ObjectMapper mapper;
  private final GeneratedSecretScanner secretScanner;
  private final PipelineRunRepository runRepository;
  private final Path root;
  private final long maxBytesPerRun;
  private final Duration retention;
  private final Object[] localLocks = new Object[256];
  private final Cache<String, Boolean> eligibility =
      Caffeine.newBuilder().maximumSize(100_000).expireAfterAccess(Duration.ofHours(8)).build();

  public BenchmarkTrajectoryStore(
      ObjectMapper mapper,
      GeneratedSecretScanner secretScanner,
      PipelineRunRepository runRepository,
      @Value("${benchmark.trajectory.root:${agent.workspace:./workspace}/benchmark-trajectories}")
          String root,
      @Value("${benchmark.trajectory.max-bytes-per-run:268435456}") long maxBytesPerRun,
      @Value("${benchmark.trajectory.retention-hours:168}") long retentionHours) {
    this.mapper = mapper;
    this.secretScanner = secretScanner;
    this.runRepository = runRepository;
    this.root = Path.of(root).toAbsolutePath().normalize();
    this.maxBytesPerRun = Math.max(1, maxBytesPerRun);
    this.retention = Duration.ofHours(Math.max(1, retentionHours));
    java.util.Arrays.setAll(localLocks, ignored -> new Object());
  }

  public void append(String runId, RunEvent event) {
    requireSafeRunId(runId);
    if (event == null) return;
    if (!isBenchmarkRun(runId)) return;
    Object lock = lockFor(runId);
    synchronized (lock) {
      try {
        Files.createDirectories(root);
        Path target = eventPath(runId);
        byte[] line = encodeRedacted(event);
        try (FileChannel channel =
                FileChannel.open(
                    target,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
            FileLock ignored = channel.lock()) {
          long current = channel.size();
          if (current + line.length > maxBytesPerRun) {
            Files.writeString(
                truncatedPath(runId),
                "trajectory exceeded " + maxBytesPerRun + " bytes at " + Instant.now() + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
            return;
          }
          ByteBuffer bytes = ByteBuffer.wrap(line);
          while (bytes.hasRemaining()) channel.write(bytes);
          channel.force(false);
        }
        cleanupExpired();
      } catch (Exception e) {
        // A missing event makes the trajectory unsuitable for submission. Persist a fail-closed
        // marker where possible and never include event payloads in logs.
        markTruncated(runId, "append failed");
        log.error(
            "Benchmark trajectory append failed for run {}: {}", runId, e.getClass().getName());
      }
    }
  }

  public ExportSnapshot prepareExport(String runId) {
    requireSafeRunId(runId);
    if (!isBenchmarkRun(runId)) {
      throw new MissingTrajectoryException("run is not eligible for benchmark trajectories");
    }
    Object lock = lockFor(runId);
    synchronized (lock) {
      Path source = eventPath(runId);
      if (Files.exists(truncatedPath(runId))) {
        throw new IncompleteTrajectoryException("benchmark trajectory is truncated");
      }
      try {
        if (!Files.isRegularFile(source) || Files.size(source) == 0) {
          throw new MissingTrajectoryException("benchmark trajectory is empty or unavailable");
        }
        long size = Files.size(source);
        if (size > maxBytesPerRun) {
          throw new IncompleteTrajectoryException("benchmark trajectory exceeds export cap");
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long events = 0;
        long remaining = size;
        try (var input = Files.newInputStream(source)) {
          byte[] buffer = new byte[64 * 1024];
          while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
              throw new IOException("trajectory shortened while preparing export");
            }
            digest.update(buffer, 0, read);
            for (int i = 0; i < read; i++) if (buffer[i] == '\n') events++;
            remaining -= read;
          }
        }
        if (events == 0) throw new MissingTrajectoryException("benchmark trajectory has no events");
        validateExportContents(source, runId, size, events);
        return new ExportSnapshot(
            runId,
            source,
            size,
            events,
            HexFormat.of().formatHex(digest.digest()),
            false,
            Files.getLastModifiedTime(source).toInstant().plus(retention));
      } catch (MissingTrajectoryException | IncompleteTrajectoryException e) {
        throw e;
      } catch (Exception e) {
        throw new IncompleteTrajectoryException("benchmark trajectory export unavailable", e);
      }
    }
  }

  /** Register a freshly-created benchmark run before its first event, avoiding a DB lookup. */
  public void registerBenchmarkRun(String runId) {
    requireSafeRunId(runId);
    eligibility.put(runId, true);
  }

  private boolean isBenchmarkRun(String runId) {
    Boolean cached = eligibility.getIfPresent(runId);
    if (cached != null) return cached;
    if (runRepository == null) return false;
    var row = runRepository.findById(runId);
    if (row.isEmpty()) return false; // do not cache a creation race
    String source = row.orElseThrow().getSourceType();
    boolean allowed =
        source != null
            && (source.equalsIgnoreCase("benchmark")
                || source.equalsIgnoreCase("TERMINAL_BENCH")
                || source.equalsIgnoreCase("SWE_BENCH")
                || source.equalsIgnoreCase("PROGRAM_BENCH"));
    eligibility.put(runId, allowed); // sourceType is immutable after run creation
    return allowed;
  }

  private Object lockFor(String runId) {
    return localLocks[(runId.hashCode() & Integer.MAX_VALUE) % localLocks.length];
  }

  /**
   * Copy exactly the immutable prefix described by {@code snapshot}; later appends are excluded.
   */
  public void copyTo(ExportSnapshot snapshot, OutputStream output) throws IOException {
    // Revalidate immediately before network egress. This closes the prepare→stream window for an
    // older/tampered artifact; the runner independently recomputes the advertised checksum too.
    validateExportContents(
        snapshot.path(), snapshot.runId(), snapshot.sizeBytes(), snapshot.eventCount());
    long remaining = snapshot.sizeBytes();
    try (var input = Files.newInputStream(snapshot.path())) {
      byte[] buffer = new byte[64 * 1024];
      while (remaining > 0) {
        int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
        if (read < 0) throw new IOException("trajectory shortened during export");
        output.write(buffer, 0, read);
        remaining -= read;
      }
    }
  }

  private byte[] encodeRedacted(RunEvent event) throws IOException {
    JsonNode safe = redact(mapper.valueToTree(event), null);
    String encoded = mapper.writeValueAsString(safe) + "\n";
    if (secretScanner.scan(encoded).isPresent()) {
      throw new IOException("recognized credential survived trajectory redaction");
    }
    return encoded.getBytes(StandardCharsets.UTF_8);
  }

  private void validateExportContents(Path source, String runId, long size, long newlineCount)
      throws IOException {
    try (var input = new BoundedInputStream(Files.newInputStream(source), size)) {
      if (secretScanner.scan(input).isPresent()) {
        throw new IOException("recognized credential found in durable trajectory");
      }
    }
    long parsed = 0;
    try (var input = new BoundedInputStream(Files.newInputStream(source), size);
        MappingIterator<JsonNode> values = mapper.readerFor(JsonNode.class).readValues(input)) {
      while (values.hasNextValue()) {
        JsonNode event = values.nextValue();
        if (!event.isObject()
            || !event.path("runId").asText().equals(runId)
            || event.path("type").asText().isBlank()) {
          throw new IOException("invalid run event in durable trajectory");
        }
        if (!redact(event, null).equals(event)) {
          throw new IOException("unsafe value found in durable trajectory");
        }
        parsed++;
      }
    }
    if (parsed != newlineCount) {
      throw new IOException("durable trajectory is not one valid JSON event per line");
    }
  }

  private JsonNode redact(JsonNode node, String fieldName) {
    String normalized =
        fieldName == null ? "" : fieldName.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
    if (SENSITIVE_FIELDS.contains(normalized)
        || normalized.endsWith("apikey")
        || normalized.endsWith("accesstoken")
        || normalized.endsWith("refreshtoken")
        || normalized.endsWith("password")
        || normalized.endsWith("secret")
        || normalized.endsWith("credential")
        || normalized.endsWith("privatekey")) {
      return TextNode.valueOf("[REDACTED]");
    }
    if (node == null || node.isNull()) return node;
    if (node.isObject()) {
      ObjectNode out = mapper.createObjectNode();
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        var field = fields.next();
        out.set(field.getKey(), redact(field.getValue(), field.getKey()));
      }
      return out;
    }
    if (node.isArray()) {
      var out = mapper.createArrayNode();
      node.forEach(value -> out.add(redact(value, fieldName)));
      return out;
    }
    if (!node.isTextual()) return node;
    String text = node.asText();
    String trimmed = text.trim();
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      try {
        JsonNode embedded = mapper.readTree(trimmed);
        if (embedded != null && embedded.isContainerNode()) {
          text = mapper.writeValueAsString(redact(embedded, null));
        }
      } catch (IOException ignored) {
        // Ordinary log text may start with punctuation. Continue with scalar redaction.
      }
    }
    text = secretScanner.redactRecognizedSecrets(text);
    text = BEARER.matcher(text).replaceAll("Bearer [REDACTED]");
    text = ASSIGNED_SECRET.matcher(text).replaceAll("$1$2[REDACTED]");
    text = SENSITIVE_PATH.matcher(text).replaceAll("[REDACTED_PATH]");
    return TextNode.valueOf(text);
  }

  private void markTruncated(String runId, String reason) {
    try {
      Files.createDirectories(root);
      Files.writeString(
          truncatedPath(runId),
          reason + " at " + Instant.now() + "\n",
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
    } catch (IOException ignored) {
      // The export will also fail when the artifact is absent; do not leak original event data.
    }
  }

  private void cleanupExpired() {
    Instant cutoff = Instant.now().minus(retention);
    try (var files = Files.list(root)) {
      files
          .filter(Files::isRegularFile)
          .filter(
              path -> {
                try {
                  return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
                } catch (IOException e) {
                  return false;
                }
              })
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException e) {
                  log.warn("Could not expire benchmark trajectory artifact {}", path.getFileName());
                }
              });
    } catch (IOException ignored) {
      // Cleanup is best-effort; size caps remain fail-closed per run.
    }
  }

  private Path eventPath(String runId) {
    return root.resolve(runId + ".events.ndjson");
  }

  private Path truncatedPath(String runId) {
    return root.resolve(runId + ".truncated");
  }

  private static void requireSafeRunId(String runId) {
    if (runId == null || !SAFE_RUN_ID.matcher(runId).matches()) {
      throw new IllegalArgumentException("invalid run id");
    }
  }

  /** Input view limited to the exact immutable prefix covered by the advertised checksum. */
  private static final class BoundedInputStream extends FilterInputStream {
    private long remaining;

    private BoundedInputStream(InputStream input, long remaining) {
      super(input);
      this.remaining = remaining;
    }

    @Override
    public int read() throws IOException {
      if (remaining <= 0) return -1;
      int value = super.read();
      if (value >= 0) remaining--;
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      if (remaining <= 0) return -1;
      int read = super.read(bytes, offset, (int) Math.min(length, remaining));
      if (read > 0) remaining -= read;
      return read;
    }
  }

  public record ExportSnapshot(
      String runId,
      Path path,
      long sizeBytes,
      long eventCount,
      String sha256,
      boolean truncated,
      Instant retainedUntil) {}

  public static class MissingTrajectoryException extends RuntimeException {
    public MissingTrajectoryException(String message) {
      super(message);
    }
  }

  public static class IncompleteTrajectoryException extends RuntimeException {
    public IncompleteTrajectoryException(String message) {
      super(message);
    }

    public IncompleteTrajectoryException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
