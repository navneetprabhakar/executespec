package com.navneet.executespec.deployment.preview;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
@RequiredArgsConstructor
public class PreviewPortPool {

    private final PreviewProperties props;
    private final DockerExecutor docker;

    /** port → runId */
    private final ConcurrentHashMap<Integer, String> allocated = new ConcurrentHashMap<>();

    /**
     * Allocate the next available port for a run.
     */
    public synchronized int allocate(String runId) {
        for (int port = props.getPortRangeStart(); port <= props.getPortRangeEnd(); port++) {
            if (!allocated.containsKey(port)) {
                allocated.put(port, runId);
                log.debug("Allocated port {} for run {}", port, runId);
                return port;
            }
        }
        throw new PreviewException("No preview ports available (range " +
                props.getPortRangeStart() + "-" + props.getPortRangeEnd() + " exhausted)");
    }

    /**
     * Release a specific port.
     */
    public void release(int port) {
        String removed = allocated.remove(port);
        if (removed != null) {
            log.debug("Released port {} (was run {})", port, removed);
        }
    }

    /**
     * Release all ports allocated to a given runId.
     */
    public void releaseByRunId(String runId) {
        allocated.entrySet().removeIf(entry -> {
            if (runId.equals(entry.getValue())) {
                log.debug("Released port {} for run {}", entry.getKey(), runId);
                return true;
            }
            return false;
        });
    }

    /**
     * Number of currently allocated ports.
     */
    public int activeCount() {
        return allocated.size();
    }

    /**
     * Reconcile on startup: discover running preview containers that survived a JVM restart,
     * and register their ports in our in-memory map.
     */
    @PostConstruct
    void reconcile() {
        if (!docker.isDockerAvailable()) {
            log.info("Docker not available — skipping preview port reconciliation");
            return;
        }

        List<String> containers = docker.listPreviewContainers();
        int reconciled = 0;

        for (String containerName : containers) {
            // Container names: preview-<runId> or preview-<runIdPrefix>
            String runId = containerName.startsWith("preview-")
                    ? containerName.substring("preview-".length())
                    : containerName;

            // Try common container ports to find the mapped host port
            for (PreviewProjectType type : PreviewProjectType.values()) {
                int hostPort = docker.inspectHostPort(containerName, type.getContainerPort());
                if (hostPort > 0 && hostPort >= props.getPortRangeStart() && hostPort <= props.getPortRangeEnd()) {
                    allocated.put(hostPort, runId);
                    reconciled++;
                    log.info("Reconciled orphan container {} → port {}", containerName, hostPort);
                    break;
                }
            }
        }

        if (reconciled > 0) {
            log.info("Reconciled {} orphan preview containers from previous JVM run", reconciled);
        }
    }
}
