package com.navneet.executespec.contracts.detection;

import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.contracts.ContractRegistry;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * E2.2 — detects AsyncAPI specs. Filename match plus a content sniff for the {@code asyncapi:} root
 * key.
 */
@Component
@Slf4j
public class AsyncApiDetector implements FileBasedContractDetector {

  private static final List<String> PATTERNS =
      List.of(
          "**/*.asyncapi.yaml",
          "**/*.asyncapi.yml",
          "**/*.asyncapi.json",
          "**/asyncapi.yaml",
          "**/asyncapi.yml",
          "**/asyncapi.json");

  @Override
  public String detectorName() {
    return "AsyncApiDetector";
  }

  @Override
  public ContractRegistry.ContractType type() {
    return ContractRegistry.ContractType.ASYNCAPI;
  }

  @Override
  public List<String> filePatterns() {
    return PATTERNS;
  }

  @Override
  public List<DetectedContract> detect(List<String> repoFilePaths, ContentLoader loader) {
    if (repoFilePaths == null || repoFilePaths.isEmpty()) return List.of();
    List<DetectedContract> out = new ArrayList<>();
    for (String path : repoFilePaths) {
      if (!matchesAny(path)) continue;
      try {
        String content = loader.load(path);
        if (content == null || content.isBlank()) continue;
        if (!content.contains("asyncapi:") && !content.contains("\"asyncapi\"")) continue;
        out.add(
            new DetectedContract(
                DetectorUtils.basenameWithoutExtension(path),
                type(),
                path,
                content,
                DetectorUtils.sha256(content),
                DetectorUtils.pullOpenApiVersion(content)));
      } catch (Exception ex) {
        log.warn("[AsyncApiDetector] failed to read {}: {}", path, ex.getMessage());
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
