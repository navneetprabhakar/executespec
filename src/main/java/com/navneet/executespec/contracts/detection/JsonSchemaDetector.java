package com.navneet.executespec.contracts.detection;

import com.navneet.executespec.contracts.ContractRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * E2.2 — detects JSON Schema files. Required content sniff: must declare
 * {@code "$schema": "..."} per JSON Schema's own conventions. Pure
 * filename matches without a {@code $schema} key are ignored to avoid
 * accidentally registering arbitrary {@code .schema.json} configs.
 */
@Component
@Slf4j
public class JsonSchemaDetector implements FileBasedContractDetector {

    private static final List<String> PATTERNS = List.of(
            "**/*.schema.json",
            "**/schemas/*.json",
            "**/json-schemas/*.json");

    @Override public String detectorName() { return "JsonSchemaDetector"; }
    @Override public ContractRegistry.ContractType type() { return ContractRegistry.ContractType.JSON_SCHEMA; }
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
                if (!content.contains("\"$schema\"")) continue; // strict gate
                out.add(new DetectedContract(
                        DetectorUtils.basenameWithoutExtension(path),
                        type(),
                        path,
                        content,
                        DetectorUtils.sha256(content),
                        null));
            } catch (Exception ex) {
                log.warn("[JsonSchemaDetector] failed to read {}: {}", path, ex.getMessage());
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
}
