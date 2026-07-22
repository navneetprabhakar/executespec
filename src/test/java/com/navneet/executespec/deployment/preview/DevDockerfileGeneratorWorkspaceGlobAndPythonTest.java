package com.navneet.executespec.deployment.preview;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RR-387 (npm/Yarn/pnpm workspace globs) + RR-388 (bare Python impossible COPY). Monorepo detection
 * used to treat each {@code workspaces[]} value as a literal directory, so {@code packages/*} never
 * matched; and both Python Dockerfiles unconditionally emitted {@code COPY requirements.txt*
 * pyproject.toml* ./}, which aborts the build when neither manifest exists.
 */
class DevDockerfileGeneratorWorkspaceGlobAndPythonTest {

  private final DevDockerfileGenerator gen = new DevDockerfileGenerator();
  private final ObjectMapper mapper = new ObjectMapper();

  // ---- RR-387 ----

  @Test
  void expandsTrailingStarGlobToRealPackageDirs(@TempDir Path ws) throws IOException {
    Files.createDirectories(ws.resolve("packages/web"));
    Files.createDirectories(ws.resolve("packages/api"));
    Files.createDirectories(ws.resolve("packages/empty")); // no package.json — excluded
    Files.writeString(ws.resolve("packages/web/package.json"), "{\"name\":\"web\"}");
    Files.writeString(ws.resolve("packages/api/package.json"), "{\"name\":\"api\"}");

    JsonNode workspaces = mapper.readTree("[\"packages/*\"]");
    assertThat(gen.resolveWorkspaceDirs(workspaces, ws))
        .containsExactly("packages/api", "packages/web"); // sorted + deduped, empty excluded
  }

  @Test
  void supportsYarnObjectForm(@TempDir Path ws) throws IOException {
    Files.createDirectories(ws.resolve("apps/site"));
    Files.writeString(ws.resolve("apps/site/package.json"), "{\"name\":\"site\"}");

    JsonNode workspaces = mapper.readTree("{\"packages\":[\"apps/*\"]}");
    assertThat(gen.resolveWorkspaceDirs(workspaces, ws)).containsExactly("apps/site");
  }

  @Test
  void literalWorkspaceEntryStillResolves(@TempDir Path ws) throws IOException {
    Files.createDirectories(ws.resolve("frontend"));
    Files.writeString(ws.resolve("frontend/package.json"), "{\"name\":\"fe\"}");

    JsonNode workspaces = mapper.readTree("[\"frontend\"]");
    assertThat(gen.resolveWorkspaceDirs(workspaces, ws)).containsExactly("frontend");
  }

  @Test
  void rejectsTraversingPattern(@TempDir Path ws) throws IOException {
    JsonNode workspaces = mapper.readTree("[\"../escape/*\", \"/abs/*\"]");
    assertThat(gen.resolveWorkspaceDirs(workspaces, ws)).isEmpty();
  }

  // ---- RR-388 ----

  @Test
  void pythonWithoutManifestEmitsNoManifestCopy(@TempDir Path ws) throws IOException {
    // A bare Python script — no requirements.txt, no pyproject.toml.
    Files.writeString(ws.resolve("app.py"), "print('hi')\n");

    String dockerfile = gen.generateForCloudRun(PreviewProjectType.PYTHON, ws);
    assertThat(dockerfile).doesNotContain("COPY requirements.txt*");
    assertThat(dockerfile).contains("COPY . ."); // the whole tree is still copied
  }

  @Test
  void pythonWithRequirementsEmitsManifestCopy(@TempDir Path ws) throws IOException {
    Files.writeString(ws.resolve("app.py"), "print('hi')\n");
    Files.writeString(ws.resolve("requirements.txt"), "flask\n");

    String dockerfile = gen.generateForCloudRun(PreviewProjectType.PYTHON, ws);
    assertThat(dockerfile).contains("COPY requirements.txt* pyproject.toml* ./");
  }
}
