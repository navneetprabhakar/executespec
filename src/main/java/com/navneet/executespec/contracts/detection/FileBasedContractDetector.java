package com.navneet.executespec.contracts.detection;

import java.util.List;

import com.navneet.executespec.contracts.ContractRegistry;

/**
 * E2.2 — strategy interface for file-based contract detection.
 *
 * <p>Each implementation handles one contract format (OpenAPI, Proto, GraphQL, JSON Schema,
 * AsyncAPI). The detector takes the full file tree of a repo + a content-loader callback (so each
 * file's bytes are loaded only when the path actually matches a relevant pattern — most files in a
 * repo are ignored).
 *
 * <p>Implementations must be stateless and thread-safe; one instance is shared across all repos.
 * Implementations should NOT throw on individual file failures — log and skip so a single corrupt
 * YAML doesn't block detection on the rest of the repo.
 */
public interface FileBasedContractDetector {

  /**
   * Format-specific name shown in the coverage panel; matches one of the enum values in {@link
   * ContractRegistry.ContractType}.
   */
  String detectorName();

  ContractRegistry.ContractType type();

  /**
   * The file-pattern globs this detector matches (rendered in the coverage panel even when nothing
   * was found, so the customer sees what was scanned).
   */
  List<String> filePatterns();

  /**
   * Walk the repo's file tree and return one {@link DetectedContract} per file/contract found.
   * Implementations should:
   *
   * <ul>
   *   <li>Filter the input list by {@link #filePatterns()}
   *   <li>Use the content loader to fetch only matching files
   *   <li>Return an empty list when nothing matches (never throw)
   * </ul>
   */
  List<DetectedContract> detect(List<String> repoFilePaths, ContentLoader loader);

  /** Test seam — substitute the GH content fetch with anything callable. */
  @FunctionalInterface
  interface ContentLoader {
    /** Load the UTF-8 content of a single file. Returns null on missing/error. */
    String load(String relativePath);
  }
}
