package com.navneet.executespec.deployment.preview;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PreviewDockerfileSnippetsTest {

  @Test
  void cloudRunNonRootUserUsesAlpineCommandsWhenRequested() {
    assertThat(PreviewDockerfileSnippets.cloudRunNonRootUser(true))
        .isEqualTo(
            "RUN addgroup -S app && adduser -S app -G app && chown -R app:app /app\n"
                + "USER app\n");
  }

  @Test
  void cloudRunNonRootUserUsesDebianCommandsWhenNotAlpine() {
    assertThat(PreviewDockerfileSnippets.cloudRunNonRootUser(false))
        .isEqualTo(
            "RUN groupadd -r app && useradd -r -g app -s /sbin/nologin app && chown -R"
                + " app:app /app\n"
                + "USER app\n");
  }

  @Test
  void viteAllowedHostsPatchOnlyCreatesConfigWhenViteIsDirectDependency() {
    String patch = PreviewDockerfileSnippets.viteAllowedHostsPatch();

    assertThat(patch)
        .startsWith("RUN node -e")
        .contains("allowedHosts:true")
        .contains("hasViteDirect")
        .contains("p.dependencies&&p.dependencies.vite")
        .contains("p.devDependencies&&p.devDependencies.vite");
  }

  @Test
  void viteProxyPatchTargetsRequestedPort() {
    assertThat(PreviewDockerfileSnippets.viteProxyPatch(8081))
        .contains("localhost:8081")
        .contains("127.0.0.1:8081")
        .doesNotContain("localhost:3001");
  }

  @Test
  void viteDevScriptPatchInjectsHostAndPortAndCreatesDevAll() {
    assertThat(PreviewDockerfileSnippets.viteDevScriptPatch(8080))
        .contains("vite --host 0.0.0.0 --port 8080")
        .contains("p.scripts['dev:all']")
        .contains("concurrently");
  }
}
