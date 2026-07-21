package com.navneet.executespec.deployment.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression pins for the 2026-07-20 preview hardening pass (RR-156, RR-158, RR-476).
 *
 * <p>Each nested class corresponds to one finding and asserts the property the finding said was
 * missing, not merely that the code runs.
 */
class PreviewHardeningTest {

  @Nested
  @DisplayName("RR-156 — Compose input cannot inject directives")
  class ComposeInjection {

    private final ComposeFileGenerator gen = new ComposeFileGenerator(new PreviewProperties());

    private Map<String, String> env(String key, String value) {
      Map<String, String> m = new LinkedHashMap<>();
      m.put(key, value);
      return m;
    }

    @Test
    @DisplayName("an env NAME carrying YAML structure is refused")
    void rejectsStructuredEnvName(@TempDir Path ws) {
      assertThatThrownBy(
              () ->
                  gen.generate(
                      ws,
                      PreviewProjectType.NODE_BACKEND,
                      PreviewMode.REST,
                      null,
                      env("X: \"1\"\n    privileged", "true"),
                      10000))
          .isInstanceOf(PreviewException.class);
    }

    @Test
    @DisplayName("an env VALUE carrying a newline is refused")
    void rejectsNewlineInValue(@TempDir Path ws) {
      assertThatThrownBy(
              () ->
                  gen.generate(
                      ws,
                      PreviewProjectType.NODE_BACKEND,
                      PreviewMode.REST,
                      null,
                      env("OK_NAME", "v\"\n    privileged: true\n    x: \""),
                      10000))
          .isInstanceOf(PreviewException.class);
    }

    @Test
    @DisplayName("a Docker-socket bind mount cannot be smuggled through a value")
    void rejectsVolumeSmuggling(@TempDir Path ws) {
      assertThatThrownBy(
              () ->
                  gen.generate(
                      ws,
                      PreviewProjectType.NODE_BACKEND,
                      PreviewMode.REST,
                      null,
                      env(
                          "OK_NAME",
                          "x\"\n    volumes:\n"
                              + "      - \"/var/run/docker.sock:/var/run/docker.sock\"\n"
                              + "    y: \""),
                      10000))
          .isInstanceOf(PreviewException.class);
    }

    @Test
    @DisplayName("ordinary values still generate a valid, hardened compose file")
    void generatesNormally(@TempDir Path ws) throws Exception {
      Path out =
          gen.generate(
              ws,
              PreviewProjectType.NODE_BACKEND,
              PreviewMode.REST,
              null,
              env("API_BASE", "https://example.test/a b:c"),
              10000);
      String body = Files.readString(out);
      assertThat(body).contains("API_BASE:").contains("no-new-privileges:true");
      assertThat(body).doesNotContain("privileged: true");
    }
  }

  @Nested
  @DisplayName("RR-158 — preview host routing is an exact capability")
  class HostRouting {

    private PreviewPortPool pool() {
      PreviewProperties props = new PreviewProperties();
      props.setUseEphemeralPorts(false);
      return new PreviewPortPool(props);
    }

    @Test
    @DisplayName("a label is 128 bits of hex, stable per run, and unique across runs")
    void labelsAreUnguessable() {
      PreviewPortPool pool = pool();
      String a = pool.hostLabel("11111111-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
      String b = pool.hostLabel("11111111-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

      assertThat(a).hasSize(32).matches("[0-9a-f]{32}");
      assertThat(a).isNotEqualTo(b);
      assertThat(pool.hostLabel("11111111-aaaa-4aaa-8aaa-aaaaaaaaaaaa")).isEqualTo(a);
    }

    @Test
    @DisplayName("a prefix of a valid label resolves nothing — the old bug")
    void prefixesDoNotResolve() {
      PreviewPortPool pool = pool();
      String runId = "11111111-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
      int port = pool.allocate(runId);
      String label = pool.hostLabel(runId);

      assertThat(pool.findPortByHostLabel(label)).isEqualTo(port);
      assertThat(pool.findPortByHostLabel(label.substring(0, 1))).isEqualTo(-1);
      assertThat(pool.findPortByHostLabel(label.substring(0, 8))).isEqualTo(-1);
      assertThat(pool.findPortByHostLabel(runId.substring(0, 8))).isEqualTo(-1);
      assertThat(pool.findPortByHostLabel(null)).isEqualTo(-1);
      assertThat(pool.findPortByHostLabel("")).isEqualTo(-1);
    }

    @Test
    @DisplayName("releasing a preview retires its routing capability immediately")
    void releaseRetiresLabel() {
      PreviewPortPool pool = pool();
      String runId = "11111111-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
      pool.allocate(runId);
      String label = pool.hostLabel(runId);

      pool.release(runId);

      assertThat(pool.findPortByHostLabel(label)).isEqualTo(-1);
    }
  }

  @Nested
  @DisplayName("RR-688 — env detection does not read host files through workspace symlinks")
  class EnvDetectionContainment {

    private final EnvVarDetector detector = new EnvVarDetector();

    @Test
    @DisplayName("a .env.example symlinked to a host file is not parsed")
    void refusesSymlinkedEnvExample(@TempDir Path root) throws Exception {
      Path workspace = Files.createDirectory(root.resolve("ws"));
      Path hostSecrets =
          Files.writeString(root.resolve("host.env"), "HOST_LEAKED_TOKEN=super-secret-value\n");
      Files.createSymbolicLink(workspace.resolve(".env.example"), hostSecrets);

      var detected = detector.detect(workspace);

      assertThat(detected).noneMatch(v -> v.name().equals("HOST_LEAKED_TOKEN"));
    }

    @Test
    @DisplayName("a source file symlinked to a host file is not scanned")
    void refusesSymlinkedSourceFile(@TempDir Path root) throws Exception {
      Path workspace = Files.createDirectory(root.resolve("ws"));
      Path hostSource =
          Files.writeString(root.resolve("host.js"), "const x = process.env.HOST_ONLY_VAR;\n");
      Files.createSymbolicLink(workspace.resolve("app.js"), hostSource);

      var detected = detector.detect(workspace);

      assertThat(detected).noneMatch(v -> v.name().equals("HOST_ONLY_VAR"));
    }

    @Test
    @DisplayName("a symlinked directory cannot smuggle a host file into the walk")
    void refusesSymlinkedDirectory(@TempDir Path root) throws Exception {
      Path workspace = Files.createDirectory(root.resolve("ws"));
      Path hostDir = Files.createDirectory(root.resolve("hostdir"));
      Files.writeString(hostDir.resolve("leak.js"), "process.env.NESTED_HOST_VAR;\n");
      Files.createSymbolicLink(workspace.resolve("src"), hostDir);

      var detected = detector.detect(workspace);

      assertThat(detected).noneMatch(v -> v.name().equals("NESTED_HOST_VAR"));
    }

    @Test
    @DisplayName("ordinary in-workspace files are still detected")
    void detectsRealWorkspaceFiles(@TempDir Path root) throws Exception {
      Path workspace = Files.createDirectory(root.resolve("ws"));
      Files.writeString(workspace.resolve(".env.example"), "REAL_WORKSPACE_VAR=\n");
      Files.writeString(workspace.resolve("app.js"), "process.env.REAL_SOURCE_VAR;\n");

      var detected = detector.detect(workspace);

      assertThat(detected).anyMatch(v -> v.name().equals("REAL_WORKSPACE_VAR"));
      assertThat(detected).anyMatch(v -> v.name().equals("REAL_SOURCE_VAR"));
    }
  }

  @Nested
  @DisplayName("RR-476 — framework writes do not follow repository symlinks")
  class ArtifactWrites {

    @Test
    @DisplayName("a Dockerfile symlink pointing outside the workspace is refused, target untouched")
    void refusesSymlinkedTarget(@TempDir Path root) throws Exception {
      Path workspace = Files.createDirectory(root.resolve("ws"));
      Path outside = Files.writeString(root.resolve("victim.txt"), "ORIGINAL");
      Files.createSymbolicLink(workspace.resolve("Dockerfile"), outside);

      assertThatThrownBy(() -> PreviewArtifactWriter.write(workspace, "Dockerfile", "FROM scratch"))
          .isInstanceOf(PreviewException.class);

      assertThat(Files.readString(outside)).isEqualTo("ORIGINAL");
    }

    @Test
    @DisplayName("an ordinary artifact write still succeeds and overwrites")
    void writesNormally(@TempDir Path root) throws Exception {
      Path workspace = Files.createDirectory(root.resolve("ws"));
      Files.writeString(workspace.resolve("Dockerfile"), "OLD CONTENT THAT IS LONGER");

      assertThatCode(() -> PreviewArtifactWriter.write(workspace, "Dockerfile", "FROM scratch"))
          .doesNotThrowAnyException();

      assertThat(Files.readString(workspace.resolve("Dockerfile"))).isEqualTo("FROM scratch");
    }

    @Test
    @DisplayName("a traversing relative name cannot escape the workspace")
    void refusesTraversal(@TempDir Path root) throws Exception {
      Path workspace = Files.createDirectory(root.resolve("ws"));
      Files.writeString(root.resolve("victim.txt"), "ORIGINAL");

      assertThatThrownBy(() -> PreviewArtifactWriter.write(workspace, "../victim.txt", "clobbered"))
          .isInstanceOf(PreviewException.class);

      assertThat(Files.readString(root.resolve("victim.txt"))).isEqualTo("ORIGINAL");
    }
  }
}
