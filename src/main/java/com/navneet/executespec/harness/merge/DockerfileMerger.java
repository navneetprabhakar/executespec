package com.navneet.executespec.harness.merge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Deterministic merger for Dockerfiles.
 *
 * <p>Strategy: Dockerfiles are often scaffolded then patched by copilot.
 * If both changed, we generally favor 'theirs' (the copilot fix) over 'ours'
 * (the original scaffold) if 'ours' didn't change from 'base'. If both changed
 * from base, we favor 'theirs' as it's the latest intent.
 */
@Component
@Order(5)
@Slf4j
public class DockerfileMerger implements SpecializedMerger {

    @Override
    public boolean canHandle(String filePath) {
        return filePath.endsWith("Dockerfile");
    }

    @Override
    public Optional<String> merge(String base, String ours, String theirs) {
        if (ours == null || ours.isBlank()) return Optional.ofNullable(theirs);
        if (theirs == null || theirs.isBlank()) return Optional.of(ours);

        if (ours.equals(theirs)) {
            return Optional.of(ours);
        }

        // For Dockerfiles, copilot apply/fix branches (theirs) usually contain fixes
        // we want to preserve over the original task (ours).
        log.info("Dockerfile conflict: favoring 'theirs' version");
        return Optional.of(theirs);
    }
}
