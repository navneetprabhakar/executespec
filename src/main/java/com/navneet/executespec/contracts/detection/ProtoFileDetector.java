package com.navneet.executespec.contracts.detection;

import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.contracts.ContractRegistry;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * E2.2 — detects {@code .proto} files. Each {@code .proto} becomes one contract row keyed by
 * basename (without {@code .proto}).
 */
@Component
@Slf4j
public class ProtoFileDetector implements FileBasedContractDetector {

  private static final List<String> PATTERNS = List.of("**/*.proto");

  @Override
  public String detectorName() {
    return "ProtoFileDetector";
  }

  @Override
  public ContractRegistry.ContractType type() {
    return ContractRegistry.ContractType.PROTO;
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
      if (!DetectorUtils.matchesGlob(path, PATTERNS.get(0))) continue;
      try {
        String content = loader.load(path);
        if (content == null || content.isBlank()) continue;
        // Fast sanity — must declare syntax = "proto2|proto3" or contain
        // service/message/rpc keyword. Filters out files merely named .proto.
        if (!content.contains("syntax")
            && !content.contains("message ")
            && !content.contains("service ")) {
          continue;
        }
        out.add(
            new DetectedContract(
                DetectorUtils.basenameWithoutExtension(path),
                type(),
                path,
                content,
                DetectorUtils.sha256(content),
                DetectorUtils.pullProtoVersion(content)));
      } catch (Exception ex) {
        log.warn("[ProtoFileDetector] failed to read {}: {}", path, ex.getMessage());
      }
    }
    return out;
  }
}
