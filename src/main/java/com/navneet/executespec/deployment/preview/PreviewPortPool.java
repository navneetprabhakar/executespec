package com.navneet.executespec.deployment.preview;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Allocates host ports for preview containers from a configurable range.
 * Thread-safe — backed by ConcurrentHashMap.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PreviewPortPool {

    private final PreviewProperties props;

    /** runId → allocated host port */
    private final ConcurrentHashMap<String, Integer> allocations = new ConcurrentHashMap<>();

    /** Ports reserved but not yet bound by Docker — prevents TOCTOU between allocate() and Docker bind. */
    private final java.util.Set<Integer> reservedPorts = ConcurrentHashMap.newKeySet();

    /**
     * Allocate a free port for the given run.
     * Uses OS ephemeral port assignment (ServerSocket(0)) to avoid conflicts
     * with firewalls, TIME_WAIT states, and other processes.
     * Falls back to configured range if ephemeral allocation fails.
     * Idempotent — returns existing allocation if already allocated.
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

        throw new PreviewException("No preview ports available — ephemeral allocation failed and range "
                + start + "-" + end + " exhausted (" + allocations.size() + " active).");
    }

    /**
     * Let the OS assign a free ephemeral port. Binds to port 0, reads the
     * assigned port, then closes the socket. The port may be reused by Docker
     * before another process grabs it (small race window, but much more
     * reliable than scanning a fixed range).
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

    /**
     * Verify that a port is actually free on the host by attempting to bind to it.
     */
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
     * Find the port allocated for a runId that starts with the given prefix.
     * Used by PreviewProxyFilter to resolve short IDs (first 8 chars of runId)
     * from preview-*.executespec.ai Host headers.
     *
     * @return allocated port, or -1 if no match found
     */
    public int findPortByPrefix(String prefix) {
        for (var entry : allocations.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                return entry.getValue();
            }
        }
        return -1;
    }
}
