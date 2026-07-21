package com.navneet.executespec.deployment.preview;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Allocates host ports for preview containers from a configurable range. Thread-safe — backed by
 * ConcurrentHashMap.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PreviewPortPool {

  private final PreviewProperties props;

  /** runId → allocated host port */
  private final ConcurrentHashMap<String, Integer> allocations = new ConcurrentHashMap<>();

  /**
   * runId → unguessable preview host label (RR-158). Separate from {@link #allocations} because the
   * label is the routing capability, not an identifier that can be derived from the run.
   */
  private final ConcurrentHashMap<String, String> hostLabels = new ConcurrentHashMap<>();

  private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

  /** Exactly 32 lowercase hex characters — 128 bits. Nothing else is a routable preview host. */
  private static final java.util.regex.Pattern LABEL_PATTERN =
      java.util.regex.Pattern.compile("[0-9a-f]{32}");

  /**
   * Ports reserved but not yet bound by Docker — prevents TOCTOU between allocate() and Docker
   * bind.
   */
  private final java.util.Set<Integer> reservedPorts = ConcurrentHashMap.newKeySet();

  /**
   * Allocate a free port for the given run. Uses OS ephemeral port assignment (ServerSocket(0)) to
   * avoid conflicts with firewalls, TIME_WAIT states, and other processes. Falls back to configured
   * range if ephemeral allocation fails. Idempotent — returns existing allocation if already
   * allocated.
   *
   * @throws PreviewException if no ports are available
   */
  public synchronized int allocate(String runId) {
    // Return existing allocation (idempotent)
    Integer existing = allocations.get(runId);
    if (existing != null) return existing;

    // Strategy 1: Let OS pick a free ephemeral port (most reliable)
    if (props.isUseEphemeralPorts()) {
      int port = allocateEphemeralPort();
      if (port > 0 && !reservedPorts.contains(port)) {
        reservedPorts.add(port);
        allocations.put(runId, port);
        log.info("Allocated ephemeral port {} for preview run {}", port, runId);
        return port;
      }
    }

    // Strategy 2: Scan configured range as fallback
    int start = props.getPortRangeStart();
    int end = props.getPortRangeEnd();
    for (int p = start; p <= end; p++) {
      if (!allocations.containsValue(p) && !reservedPorts.contains(p) && isPortAvailable(p)) {
        reservedPorts.add(p);
        allocations.put(runId, p);
        log.info("Allocated range port {} for preview run {}", p, runId);
        return p;
      }
    }

    throw new PreviewException(
        "No preview ports available — ephemeral allocation failed and range "
            + start
            + "-"
            + end
            + " exhausted ("
            + allocations.size()
            + " active).");
  }

  /**
   * Let the OS assign a free ephemeral port. Binds to port 0, reads the assigned port, then closes
   * the socket. The port may be reused by Docker before another process grabs it (small race
   * window, but much more reliable than scanning a fixed range).
   */
  private int allocateEphemeralPort() {
    try (ServerSocket ss = new ServerSocket(0)) {
      ss.setReuseAddress(true);
      int port = ss.getLocalPort();
      // Avoid very low ports or ports already tracked by us
      if (port > 1024 && !allocations.containsValue(port)) {
        return port;
      }
    } catch (IOException e) {
      log.warn("Ephemeral port allocation failed: {}", e.getMessage());
    }
    return -1;
  }

  /** Verify that a port is actually free on the host by attempting to bind to it. */
  private boolean isPortAvailable(int port) {
    try (ServerSocket ss = new ServerSocket(port)) {
      ss.setReuseAddress(true);
      return true;
    } catch (IOException e) {
      log.debug("Port {} is not available: {}", port, e.getMessage());
      return false;
    }
  }

  public void release(String runId) {
    // Retire the routing capability with the allocation so a stopped preview's host stops
    // resolving immediately.
    hostLabels.remove(runId);
    Integer port = allocations.remove(runId);
    if (port != null) {
      reservedPorts.remove(port);
      log.info("Released port {} for preview run {}", port, runId);
    }
  }

  public int activeCount() {
    return allocations.size();
  }

  /** Get the port allocated for a run, or -1 if not allocated. */
  public int getPort(String runId) {
    return allocations.getOrDefault(runId, -1);
  }

  /**
   * Host label for a run's preview, minted on first use and stable for the life of the allocation.
   *
   * <p>RR-158: the proxy used to resolve {@code preview-<shortId>} by scanning for the first runId
   * with that <em>prefix</em>, so a single character reached whichever active preview happened to
   * sort first — roughly sixteen unauthenticated requests to land inside another tenant's preview.
   * The routing key is now an unguessable 128-bit capability that is matched exactly, and it is
   * deliberately not derived from the runId so possession of a run id is not possession of the
   * preview.
   */
  public String hostLabel(String runId) {
    return hostLabels.computeIfAbsent(runId, k -> mintLabel());
  }

  /**
   * Resolve a preview port from a host label. Exact match only — no prefix, no partial. Returns -1
   * for an unknown, expired or malformed label.
   */
  public int findPortByHostLabel(String label) {
    if (label == null || !LABEL_PATTERN.matcher(label).matches()) {
      return -1;
    }
    String runId =
        hostLabels.entrySet().stream()
            .filter(e -> e.getValue().equals(label))
            .map(java.util.Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    if (runId == null) {
      return -1;
    }
    return allocations.getOrDefault(runId, -1);
  }

  private String mintLabel() {
    byte[] raw = new byte[16];
    RANDOM.nextBytes(raw);
    return java.util.HexFormat.of().formatHex(raw);
  }
}
