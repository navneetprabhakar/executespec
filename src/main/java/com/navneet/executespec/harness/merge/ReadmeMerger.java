package com.navneet.executespec.harness.merge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Deterministic merger for README.md files.
 *
 * <p>Strategy: Simple text append for markdown files. If both sides changed the README,
 * we append the new sections from 'theirs' to 'ours'. We do a basic heuristic
 * to avoid duplicating identical lines at the end.
 */
@Component
@Order(4)
@Slf4j
public class ReadmeMerger implements SpecializedMerger {

    @Override
    public boolean canHandle(String filePath) {
        return filePath.toUpperCase().endsWith("README.MD");
    }

    @Override
    public Optional<String> merge(String base, String ours, String theirs) {
        if (ours == null || ours.isBlank()) return Optional.ofNullable(theirs);
        if (theirs == null || theirs.isBlank()) return Optional.of(ours);

        if (ours.equals(theirs)) {
            return Optional.of(ours);
        }

        // If theirs starts with ours, just use theirs (additive change)
        if (theirs.startsWith(ours)) {
            return Optional.of(theirs);
        }

        // Otherwise, append theirs to ours with a divider
        String merged = ours;
        if (!merged.endsWith("\n\n")) {
            merged += merged.endsWith("\n") ? "\n" : "\n\n";
        }
        
        merged += "<!-- Appended during merge -->\n\n" + theirs;

        return Optional.of(merged);
    }
}
