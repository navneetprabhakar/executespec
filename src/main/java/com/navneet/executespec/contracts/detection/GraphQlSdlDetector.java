package com.navneet.executespec.contracts.detection;

import com.navneet.executespec.contracts.ContractRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * E2.2 — detects GraphQL SDL files. Filename match plus a content sniff
 * for {@code type Query}, {@code type Mutation}, or {@code schema {} —
 * enough to filter out unrelated {@code .graphql} files (rare but
 * possible).
 */
@Component
@Slf4j
public class GraphQlSdlDetector implements FileBasedContractDetector {

    private static final List<String> PATTERNS = List.of(
            "**/*.graphql",
            "**/*.graphqls",
            "**/schema.graphql");

    @Override public String detectorName() { return "GraphQlSdlDetector"; }
    @Override public ContractRegistry.ContractType type() { return ContractRegistry.ContractType.GRAPHQL; }
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
                if (!looksLikeSdl(content)) continue;
                out.add(new DetectedContract(
                        DetectorUtils.basenameWithoutExtension(path),
                        type(),
                        path,
                        content,
                        DetectorUtils.sha256(content),
                        null));
            } catch (Exception ex) {
                log.warn("[GraphQlSdlDetector] failed to read {}: {}", path, ex.getMessage());
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

    private boolean looksLikeSdl(String content) {
        return content.contains("type Query")
                || content.contains("type Mutation")
                || content.contains("type Subscription")
                || content.contains("schema {")
                || content.contains("interface ");
    }
}
