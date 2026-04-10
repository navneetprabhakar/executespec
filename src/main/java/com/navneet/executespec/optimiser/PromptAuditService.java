package com.navneet.executespec.optimiser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.PromptVersioningService;
import com.navneet.executespec.state.entity.PromptAuditEntry;
import com.navneet.executespec.state.repository.PromptAuditRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PromptAuditService {

    private final PromptAuditRepository repository;
    private final ObjectMapper objectMapper;
    private final PromptVersioningService versioningService;
    private final MeterRegistry meterRegistry;

    public void record(
        String promptFile,
        String previousContent,
        String newContent,
        List<String> patternsMatched,
        List<String> sourceRunIds,
        String triggerType
    ) {
        int nextVersion = repository
            .findTopByPromptFileOrderByVersionDesc(promptFile)
            .map(e -> e.getVersion() + 1)
            .orElse(1);

        PromptAuditEntry entry = PromptAuditEntry.builder()
            .id(UUID.randomUUID().toString())
            .promptFile(promptFile)
            .version(nextVersion)
            .previousContent(previousContent)
            .newContent(newContent)
            .diffSummary(buildDiff(previousContent, newContent))
            .patternsMatched(toJson(patternsMatched))
            .sourceRunIds(toJson(sourceRunIds))
            .triggerType(triggerType)
            .appliedAt(Instant.now())
            .runsAfter(0)
            .failuresAfter(0)
            .build();

        Counter.builder("executespec.prompt.optimisation.applied")
                .tag("prompt", promptFile)
                .tag("trigger", triggerType)
                .register(meterRegistry).increment();

        repository.save(entry);
    }

    public void recordRunOutcome(boolean hadFailures) {
        repository.findAllLatestPerFile().forEach(entry -> {
            entry.setRunsAfter(entry.getRunsAfter() + 1);
            if (hadFailures) entry.setFailuresAfter(entry.getFailuresAfter() + 1);
            repository.save(entry);
        });
        versioningService.checkAndRollbackIfDegraded();
    }

    public List<PromptAuditEntry> getHistory(String promptFile) {
        return promptFile != null
            ? repository.findByPromptFileOrderByVersionDesc(promptFile)
            : repository.findAllByOrderByAppliedAtDesc();
    }

    private String buildDiff(String before, String after) {
        var beforeLines = List.of(before.split("\n"));
        var afterLines  = List.of(after.split("\n"));

        var removed = beforeLines.stream()
            .filter(l -> !afterLines.contains(l))
            .map(l -> "- " + l)
            .collect(java.util.stream.Collectors.joining("\n"));

        var added = afterLines.stream()
            .filter(l -> !beforeLines.contains(l))
            .map(l -> "+ " + l)
            .collect(java.util.stream.Collectors.joining("\n"));

        return (removed.isBlank() ? "" : removed + "\n") + added;
    }

    private String toJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }
}
