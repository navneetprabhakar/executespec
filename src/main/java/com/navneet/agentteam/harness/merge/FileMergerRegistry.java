package com.navneet.agentteam.harness.merge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Routes conflicted files to the appropriate {@link SpecializedMerger}.
 * If no merger matches, returns empty — the caller falls through to the LLM resolver.
 */
@Component
@Slf4j
public class FileMergerRegistry {

    private final List<SpecializedMerger> mergers;

    public FileMergerRegistry(List<SpecializedMerger> mergers) {
        this.mergers = mergers;
        log.info("FileMergerRegistry initialized with {} specialized mergers", mergers.size());
    }

    /**
     * Attempts deterministic merge for the given file.
     *
     * @return merged content, or empty if no merger can handle this file type
     */
    public Optional<String> tryMerge(String filePath, String base, String ours, String theirs) {
        return mergers.stream()
            .filter(m -> m.canHandle(filePath))
            .findFirst()
            .flatMap(m -> {
                log.debug("Trying specialized merger {} for {}", m.getClass().getSimpleName(), filePath);
                Optional<String> result = m.merge(base, ours, theirs);
                if (result.isPresent()) {
                    log.info("Specialized merger {} resolved conflict in {}", m.getClass().getSimpleName(), filePath);
                }
                return result;
            });
    }
}
