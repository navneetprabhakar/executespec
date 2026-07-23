package com.navneet.executespec.testgen;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** RR-301 — route mining must ignore non-runtime trees (tests, vendored deps, build output). */
class ApiSurfaceExtractorRuntimeScopeTest {

  private final ApiSurfaceExtractor extractor = new ApiSurfaceExtractor();

  @Test
  void extractsRuntimeRoutesButNotTestOrVendorOrBuildRoutes() throws Exception {
    Path root = Files.createTempDirectory("es-api-surface-");
    try {
      write(
          root,
          "src/main/java/com/x/OrderController.java",
          "@GetMapping(\"/api/orders\") public void x() {}");
      // A test source referencing a fictitious endpoint must NOT be mined.
      write(
          root,
          "src/test/java/com/x/OrderControllerTest.java",
          "@PostMapping(\"/api/test-only-endpoint\") void y() {}");
      // Vendored + build output must NOT be mined.
      write(root, "node_modules/dep/index.js", "app.get('/vendor/leak', h)");
      write(root, "build/generated/Gen.java", "@GetMapping(\"/build/leak\") void z() {}");
      // A *.test.js file outside a test dir must NOT be mined either.
      write(root, "src/main/js/orders.test.js", "router.get('/spec-only', h)");

      String surface = extractor.extract(root);

      assertThat(surface).contains("/api/orders");
      assertThat(surface).doesNotContain("/api/test-only-endpoint");
      assertThat(surface).doesNotContain("/vendor/leak");
      assertThat(surface).doesNotContain("/build/leak");
      assertThat(surface).doesNotContain("/spec-only");
    } finally {
      try (var walk = Files.walk(root)) {
        walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      }
    }
  }

  private static void write(Path root, String rel, String content) throws Exception {
    Path p = root.resolve(rel);
    Files.createDirectories(p.getParent());
    Files.writeString(p, content);
  }
}
