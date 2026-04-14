package com.navneet.executespec.execution.service;

import com.navneet.executespec.codeintel.CodeMapGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Wraps {@link CodeMapGenerator} to refresh a repo's codemap after a solution
 * completes. First iteration does a full regen (cheap enough for small repos);
 * true diff-based incremental refresh is a follow-up once the symbol index
 * exposes per-file indexing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IncrementalCodeMapService {

    private final CodeMapGenerator codeMapGenerator;

    public String refresh(String repoFullName, String githubPat) {
        if (repoFullName == null || repoFullName.isBlank()) return null;
        try {
            return codeMapGenerator.generate(repoFullName, githubPat);
        } catch (Exception e) {
            log.warn("incremental codemap refresh failed for {}: {}", repoFullName, e.getMessage());
            return null;
        }
    }
}
