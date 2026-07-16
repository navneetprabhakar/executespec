package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class RepositorySearchCitationValidationTest {

  @Test
  void rejectsCitationFromDifferentInventoryFingerprint() throws Exception {
    Path root = Files.createTempDirectory("search-citation-");
    try {
      Path file = root.resolve("src.txt");
      Files.writeString(file, "stable line\n");
      RepositorySearchService service = new RepositorySearchService();
      var result =
          service.search(
              root,
              new RepositorySearchService.SearchRequest(
                  "stable", RepositorySearchService.SearchMode.EXACT, true, null, null, 50, 0));
      var hit = result.hits().getFirst();
      var forged =
          new RepositorySearchService.SearchHit(
              hit.path(),
              hit.line(),
              hit.column(),
              hit.preview(),
              hit.matchType(),
              hit.freshness(),
              hit.tokenEstimate(),
              hit.relevanceReason(),
              "forged-fingerprint");

      assertThat(service.isCitationCurrent(root, result, hit)).isTrue();
      assertThat(service.isCitationCurrent(root, result, forged)).isFalse();
    } finally {
      Files.deleteIfExists(root.resolve("src.txt"));
      Files.deleteIfExists(root);
    }
  }
}
