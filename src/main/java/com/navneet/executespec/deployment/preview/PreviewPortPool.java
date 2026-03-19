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

    /**
     * Allocate the next free port in the configured range for the given run.
     * Idempotent — returns existing allocation if already allocated.
     *
     * @throws PreviewException if no ports are available
     */
    public synchronized int allocate(String runId) {
        // Return existing allocation (idempotent)
        Integer existing = allocations.get(runId);
        if (existing != null) return existing;

        int start = props.getPortRangeStart();
        int end = props.getPortRangeEnd();

        for (int port = start; port <= end; port++) {
            if (!allocations.containsValue(port) && isPortAvailable(port)) {
                allocations.put(runId, port);
                log.info("Allocated port {} for preview run {}", port, runId);
                return port;
            }
        }

        throw new PreviewException("No preview ports available (range " + start + "-" + end
                + ", " + allocations.size() + " active). All ports in range are either allocated or in use by other processes.");
    }

    /**
     * Verify that a port is actually free on the host by attempting to bind to it.
     * Returns false if another process is already listening on the port.
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
}
