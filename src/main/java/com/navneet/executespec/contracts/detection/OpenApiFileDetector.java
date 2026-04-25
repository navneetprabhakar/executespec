package com.navneet.executespec.contracts.detection;

import com.navneet.executespec.contracts.ContractRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * E2.2 — detects OpenAPI / Swagger spec files in a repo by filename.
 * Multi-file {@code $ref} bundling is deferred (logged in plan §13);
 * each file is hashed individually. Most repos ship a single bundled
 * spec, so this hits the 80% case cleanly.
 */
@Component
@Slf4j
public class OpenApiFileDetector implements FileBasedContractDetector {

    private static final List<String> PATTERNS = List.of(
            "**/openapi.yaml",
            "**/openapi.yml",
            "**/openapi.json",
            "**/swagger.yaml",
            "**/swagger.yml",
            "**/swagger.json",
            "**/*.openapi.yaml",
            "**/*.openapi.yml",
            "**/*.openapi.json");

    @Override public String detectorName() { return "OpenApiFileDetector"; }
    @Override public ContractRegistry.ContractType type() { return ContractRegistry.ContractType.OPENAPI; }
    @Override public List<String> filePatterns() { return PATTERNS; }

    @Override
    public List<DetectedContract> detect(List<String> repoFilePaths, ContentLoader loader) {
        if (repoFilePaths == null || repoFilePaths.isEmpty()) return List.of();
        List<DetectedContract> out = new ArrayList<>();
        for (String path : repoFilePaths) {
            if (!matchesAny(path)) continue;
            try {
                String content = loader.load(path);
                if (content == null || content.isBlank()) continue;
                if (!looksLikeOpenApi(content)) {
                    log.debug("[OpenApiFileDetector] {} matched filename but not content; skipping", path);
                    continue;
                }
                out.add(new DetectedContract(
                        DetectorUtils.basenameWithoutExtension(path),
                        type(),
                        path,
                        content,
                        DetectorUtils.sha256(content),
                        DetectorUtils.pullOpenApiVersion(content)));
            } catch (Exception ex) {
                log.warn("[OpenApiFileDetector] failed to read {}: {}", path, ex.getMessage());
            }
        }
        return out;
    }

    private boolean matchesAny(String path) {
        for (String glob : PATTERNS) {
            if (DetectorUtils.matchesGlob(path, glob)) return true;
        }
        return false;
    }

    /** Filename match is necessary but not sufficient — verify the content
     *  actually declares an OpenAPI / Swagger version key. Avoids treating
     *  unrelated YAML files that happen to be named {@code openapi.yaml}
     *  as contracts. */
    private boolean looksLikeOpenApi(String content) {
        return content.contains("openapi:")
                || content.contains("\"openapi\"")
                || content.contains("swagger:")
                || content.contains("\"swagger\"");
    }
}
