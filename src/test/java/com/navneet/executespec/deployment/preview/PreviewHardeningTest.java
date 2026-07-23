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
  @DisplayName("RR-159 — preview response cannot set a cookie on the parent domain")
  class ResponseCookieDomain {

    @Test
    @DisplayName("a Domain=.executespec.ai attribute is stripped, making the cookie host-only")
    void stripsParentDomain() {
      String rewritten =
          PreviewProxyFilter.stripCookieDomain("sid=abc; Domain=.executespec.ai; Path=/; HttpOnly");
      assertThat(rewritten).doesNotContainIgnoringCase("domain=");
      assertThat(rewritten).contains("sid=abc").contains("Path=/").contains("HttpOnly");
    }

    @Test
    @DisplayName("Domain matching is case-insensitive (DOMAIN / domain / Domain)")
    void stripsRegardlessOfCase() {
      assertThat(PreviewProxyFilter.stripCookieDomain("a=b; DOMAIN=executespec.ai"))
          .doesNotContainIgnoringCase("domain");
      assertThat(PreviewProxyFilter.stripCookieDomain("a=b; domain=evil.example"))
          .doesNotContainIgnoringCase("domain");
    }

    @Test
    @DisplayName("a cookie without a Domain attribute is preserved verbatim")
    void preservesHostOnlyCookie() {
      String v = "theme=dark; Path=/; SameSite=Lax; Secure";
      assertThat(PreviewProxyFilter.stripCookieDomain(v)).isEqualTo(v);
    }

    @Test
    @DisplayName("null / empty input is returned unchanged")
    void handlesNullAndEmpty() {
      assertThat(PreviewProxyFilter.stripCookieDomain(null)).isNull();
      assertThat(PreviewProxyFilter.stripCookieDomain("")).isEmpty();
    }
  }

  @Nested
  @DisplayName("RR-384 — credential exclusions apply at every depth, not just the context root")
  class DockerIgnoreDepth {

    /**
     * A {@code .dockerignore} pattern is matched against the whole path relative to the build
     * context, so a bare {@code .env} excludes ONLY the root-level file while {@code backend/.env}
     * is still copied by {@code COPY . .}. Every security family must therefore also be emitted in
     * its {@code **&#47;} form — asserted over the WHOLE list, so a pattern added later cannot
     * quietly ship root-only.
     */
    @Test
    @DisplayName("every security pattern is emitted both at the root and recursively")
    void everySecurityPatternIsAlsoRecursive() {
      String ignore = new DevDockerfileGenerator().dockerIgnore();

      for (var family : DevDockerfileGenerator.SECURITY_EXCLUSIONS.entrySet()) {
        for (String pattern : family.getValue()) {
          assertThat(ignore.lines().anyMatch(l -> l.equals(pattern)))
              .as("root-anchored exclusion for " + pattern)
              .isTrue();
          assertThat(ignore.lines().anyMatch(l -> l.equals("**/" + pattern)))
              .as("recursive exclusion for " + pattern + " (nested secrets were being copied)")
              .isTrue();
        }
      }
    }

    @Test
    @DisplayName("the nested credential paths the finding named are covered")
    void namedNestedPathsAreCovered() {
      String ignore = new DevDockerfileGenerator().dockerIgnore();
      assertThat(ignore)
          .contains("**/.env")
          .contains("**/.npmrc")
          .contains("**/id_rsa")
          .contains("**/*.pem")
          .contains("**/.agent-context")
          .contains("**/.executespec");
    }
  }

  @Nested
  @DisplayName("RR-384 — a customer's .dockerignore is merged, not clobbered")
  class DockerIgnoreMerge {

    @Test
    @DisplayName("an absent customer file yields the generated block unchanged")
    void noCustomerFile() {
      assertThat(PreviewArtifactWriter.mergeDockerIgnore(null, "node_modules\n.env\n"))
          .isEqualTo("node_modules\n.env\n");
      assertThat(PreviewArtifactWriter.mergeDockerIgnore("   \n", "node_modules\n"))
          .isEqualTo("node_modules\n");
    }

    @Test
    @DisplayName("customer rules are preserved and the security block is appended after them")
    void preservesCustomerAndAppendsSecurity() {
      String customer = "coverage\n*.tmp\n";
      String generated = "node_modules\n.env\n";

      String merged = PreviewArtifactWriter.mergeDockerIgnore(customer, generated);

      // Customer rules survive.
      assertThat(merged).contains("coverage").contains("*.tmp");
      // Security exclusions are present.
      assertThat(merged).contains(".env");
      // The ExecuteSpec block comes AFTER the customer content, so a customer re-include
      // (evaluated earlier) cannot pull a security exclusion back in.
      assertThat(merged.indexOf("coverage"))
          .isLessThan(merged.indexOf(PreviewArtifactWriter.MERGE_MARKER));
      assertThat(merged.indexOf(PreviewArtifactWriter.MERGE_MARKER))
          .isLessThan(merged.indexOf(".env"));
    }

    @Test
    @DisplayName("a pattern present in both files is not duplicated in the merged output")
    void dedupesSharedPatterns() {
      String merged =
          PreviewArtifactWriter.mergeDockerIgnore(
              "node_modules\ncoverage\n", "node_modules\n.env\n");

      int first = merged.indexOf("node_modules");
      int last = merged.lastIndexOf("node_modules");
      assertThat(first).isEqualTo(last); // appears exactly once
      assertThat(merged).contains(".env"); // non-duplicate generated pattern still added
    }

    @Test
    @DisplayName("writing on disk keeps the customer rule and backs the original up to .user")
    void writeMergesAndBacksUp(@TempDir Path ws) throws Exception {
      Files.writeString(ws.resolve(".dockerignore"), "my-secret-dir\ncoverage\n");

      PreviewArtifactWriter.writeDockerIgnore(ws, "node_modules\n.env\n");

      String onDisk = Files.readString(ws.resolve(".dockerignore"));
      assertThat(onDisk).contains("my-secret-dir").contains(".env"); // not clobbered
      assertThat(Files.readString(ws.resolve(".dockerignore.user")))
          .isEqualTo("my-secret-dir\ncoverage\n");
    }

    @Test
    @DisplayName("no customer file → generated content written, no backup created")
    void writeWithoutCustomerFile(@TempDir Path ws) throws Exception {
      PreviewArtifactWriter.writeDockerIgnore(ws, "node_modules\n.env\n");

      assertThat(Files.readString(ws.resolve(".dockerignore"))).isEqualTo("node_modules\n.env\n");
      assertThat(Files.exists(ws.resolve(".dockerignore.user"))).isFalse();
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
