package com.navneet.executespec.contracts.detection;

import com.navneet.executespec.contracts.ContractRegistry;

/**
 * E2.2 — value object returned by {@link FileBasedContractDetector#detect}. Holds enough info for
 * the upstream {@code ContractDetectionService} to upsert into {@code contract_registry} + {@code
 * contract_version}.
 *
 * @param name stable contract identifier within the repo (typically the basename without extension)
 * @param type one of {@link ContractRegistry.ContractType}
 * @param sourcePath repo-relative path of the source file
 * @param content raw file content (UTF-8 string)
 * @param contentHash SHA-256 of {@code content}
 * @param declaredSemver advisory version pulled from the file (e.g. OpenAPI {@code info.version});
 *     may be null
 */
public record DetectedContract(
    String name,
    ContractRegistry.ContractType type,
    String sourcePath,
    String content,
    String contentHash,
    String declaredSemver) {}
