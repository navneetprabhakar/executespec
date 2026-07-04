package com.navneet.executespec.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.smoketest.BuildCommandResolver;
import com.navneet.executespec.smoketest.TestCommandResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** task 11 — deterministic repo-orientation warm start (flag worker.localization.enabled). */
class RepoOrientationServiceTest {

  @TempDir Path ws;

  private RepoOrientationService service() {
    return new RepoOrientationService(new BuildCommandResolver(), new TestCommandResolver());
  }

  @Test
  void orientsWithLayoutIdentifierHitsAndCommands() throws Exception {
    Files.createDirectories(ws.resolve("pkg/cache"));
    Files.writeString(ws.resolve("go.mod"), "module acme/widget\n");
    Files.writeString(
        ws.resolve("pkg/cache/fn_cache.go"), "package cache\nfunc NewFnCache() {}\n");
    Files.writeString(ws.resolve("pkg/cache/other.go"), "package cache\n");

    String block =
        service()
            .orient(ws, "Fix the crash: NewFnCache returns a nil map when flux_capacitor is empty");

    assertThat(block).contains("REPO ORIENTATION");
    assertThat(block).contains("advisory");
    assertThat(block).contains("pkg/");
    assertThat(block).contains("NewFnCache");
    assertThat(block).contains("pkg/cache/fn_cache.go");
    assertThat(block).contains("go test ./...");
    assertThat(block).contains("go build ./...");
  }

  @Test
  void emptyOnGreenfieldWorkspace() {
    assertThat(service().orient(ws, "Build a new REST API")).isEmpty();
  }

  @Test
  void neverThrowsOnGarbageInput() {
    assertThat(service().orient(null, "task")).isEmpty();
    assertThat(service().orient(ws.resolve("nope"), "task")).isEmpty();
  }

  @Test
  void identifierExtractionPrefersCodeShapedTokens() {
    var ids =
        RepoOrientationService.extractIdentifiers(
            "The FluxGuard.check method fails when retry_count exceeds MaxRetries; see"
                + " `config.yaml` handling in ConfigLoader");
    assertThat(ids)
        .contains("FluxGuard", "retry_count", "MaxRetries", "ConfigLoader");
    assertThat(ids).doesNotContain("method", "fails");
  }
}
