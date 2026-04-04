package com.navneet.executespec.deployment;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Workspace-level locking to prevent preview and deploy from targeting
 * the same run's workspace simultaneously.
 */
@Component
public class WorkspaceLockService {

    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ReentrantLock getLock(String runId) {
        return locks.computeIfAbsent(runId, k -> new ReentrantLock());
    }

    public void removeLock(String runId) {
        locks.remove(runId);
    }
}
