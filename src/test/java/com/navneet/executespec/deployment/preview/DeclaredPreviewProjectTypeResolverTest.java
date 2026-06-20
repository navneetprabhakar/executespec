package com.navneet.executespec.deployment.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeclaredPreviewProjectTypeResolverTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void normalizesPlannerAliasesWhenWorkspaceMatches(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("package.json"), "{\"dependencies\":{\"react\":\"^18\"}}");

    assertThat(resolve(workspace, "react")).contains(PreviewProjectType.NODE_FRONTEND);
    assertThat(resolve(workspace, "node-js-frontend")).contains(PreviewProjectType.NODE_FRONTEND);
  }

  @Test
  void mapsSpringBootAndCliAliases(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("pom.xml"), "<project />");

    assertThat(resolve(workspace, "java-spring-boot")).contains(PreviewProjectType.SPRING_BOOT);
    assertThat(resolve(workspace, "cli")).contains(PreviewProjectType.JAVA);
  }

  @Test
  void refusesPackageJsonTypesWhenPackageJsonIsMissing(@TempDir Path workspace) throws Exception {
    assertThat(resolve(workspace, "node-backend")).isEmpty();
    assertThat(resolve(workspace, "vite")).isEmpty();
  }

  @Test
  void refusesStaticHtmlDeclarationWithoutHtmlFiles(@TempDir Path workspace) throws Exception {
    assertThat(resolve(workspace, "static")).isEmpty();

    Files.writeString(workspace.resolve("index.html"), "<h1>ok</h1>");
    assertThat(resolve(workspace, "static")).contains(PreviewProjectType.STATIC_HTML);
  }

  @Test
  void upgradesDeclaredSpringBootWhenSeparateFrontendAndBackendExist(@TempDir Path workspace)
      throws Exception {
    Files.createDirectories(workspace.resolve("backend"));
    Files.createDirectories(workspace.resolve("frontend"));
    Files.writeString(workspace.resolve("backend/pom.xml"), "<project />");
    Files.writeString(
        workspace.resolve("frontend/package.json"), "{\"scripts\":{\"dev\":\"vite\"}}");

    assertThat(resolve(workspace, "java-spring-boot")).contains(PreviewProjectType.FULL_STACK);
  }

  @Test
  void upgradesDeclaredNodeBackendWhenFrontendExists(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("package.json"), "{\"dependencies\":{\"express\":\"^4\"}}");
    Files.createDirectories(workspace.resolve("frontend"));
    Files.writeString(
        workspace.resolve("frontend/package.json"), "{\"scripts\":{\"dev\":\"vite\"}}");

    assertThat(resolve(workspace, "node-backend")).contains(PreviewProjectType.FULL_STACK);
  }

  @Test
  void ignoresBlankOrUnknownDeclarations(@TempDir Path workspace) throws Exception {
    assertThat(resolve(workspace, "")).isEmpty();
    assertThat(resolve(workspace, "UNKNOWN")).isEmpty();
  }

  @Test
  void invalidDeclarationStillSurfacesForCallerFallbackLogging(@TempDir Path workspace) {
    assertThatThrownBy(() -> resolve(workspace, "not-a-real-preview-type"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static java.util.Optional<PreviewProjectType> resolve(Path workspace, String type)
      throws IOException {
    return DeclaredPreviewProjectTypeResolver.resolve(
        workspace, MAPPER.readTree("{\"project_type\":\"" + type + "\"}"));
  }
}
