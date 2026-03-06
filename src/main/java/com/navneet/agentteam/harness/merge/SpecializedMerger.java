package com.navneet.agentteam.harness.merge;

import java.util.Optional;

/**
 * Deterministic merger for known file types (pom.xml, YAML, package.json).
 * Runs before the LLM-powered ConflictResolverAgent to save tokens.
 *
 * <p>Implementations must be stateless and thread-safe.
 */
public interface SpecializedMerger {

    /** Returns true if this merger can handle the given file path. */
    boolean canHandle(String filePath);

    /**
     * Attempts to merge three versions of a file.
     *
     * @param base   common ancestor content
     * @param ours   main branch content (already merged tasks)
     * @param theirs incoming branch content (new task)
     * @return merged content, or empty if the merger cannot resolve this conflict
     */
    Optional<String> merge(String base, String ours, String theirs);
}
