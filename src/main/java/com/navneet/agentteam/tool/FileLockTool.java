package com.navneet.agentteam.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.agentteam.state.entity.FileLock;
import com.navneet.agentteam.state.repository.FileLockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class FileLockTool {

    private final FileLockRepository lockRepo;
    private final ObjectMapper mapper;

    @Value("${agent.workspace}")
    private String workspaceRoot;

    private static final String LOCK_FILE = ".agent-context/file-locks.json";

    // Claim a file for a task. Returns false if already owned by another task.
    @Transactional
    public boolean claim(String filePath, String runId, String taskId) {
        Optional<FileLock> existing = lockRepo.findById(filePath);
        if (existing.isPresent() && !existing.get().getTaskId().equals(taskId)) {
            return false; // owned by someone else
        }
        lockRepo.save(FileLock.builder()
            .filePath(filePath).runId(runId).taskId(taskId)
            .claimedAt(Instant.now()).build());
        writeLockFile(runId);
        return true;
    }

    @Transactional
    public void release(String filePath, String taskId) {
        lockRepo.findById(filePath).ifPresent(lock -> {
            if (lock.getTaskId().equals(taskId)) {
                lockRepo.deleteById(filePath);
            }
        });
    }

    public Optional<String> getOwner(String filePath) {
        return lockRepo.findById(filePath).map(FileLock::getTaskId);
    }

    @Transactional
    public void releaseAll(String runId) {
        lockRepo.deleteByRunId(runId);
    }

    // ── Tier 5: Bulk lock release ─────────────────────────────────────────────

    @Transactional
    public int releaseAllLocksForTask(String taskId) {
        List<FileLock> locks = lockRepo.findByTaskId(taskId);
        lockRepo.deleteAll(locks);
        log.info("Released {} locks for task {}", locks.size(), taskId);
        return locks.size();
    }

    @Transactional
    public int releaseAllLocksForRun(String runId) {
        List<FileLock> locks = lockRepo.findByRunId(runId);
        lockRepo.deleteAll(locks);
        log.info("Released {} locks for run {}", locks.size(), runId);
        return locks.size();
    }

    // Writes current lock state as a JSON file into the workspace (for agents to read)
    private void writeLockFile(String runId) {
        try {
            List<FileLock> locks = lockRepo.findByRunId(runId);
            Map<String, String> lockMap = locks.stream()
                .collect(Collectors.toMap(FileLock::getFilePath, FileLock::getTaskId));
            Path lockPath = Path.of(workspaceRoot, LOCK_FILE);
            Files.createDirectories(lockPath.getParent());
            Files.writeString(lockPath, mapper.writeValueAsString(lockMap));
        } catch (IOException e) {
            // non-fatal — log only
        }
    }
}
