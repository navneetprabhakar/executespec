package com.navneet.executespec.deployment.preview;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("PreviewModeDetector")
class PreviewModeDetectorTest {

  @Test
  @DisplayName("routes Expo projects to mobile preview")
  void detectsMobileExpo(@TempDir Path workspace) {
    assertThat(PreviewModeDetector.detect(PreviewProjectType.REACT_NATIVE_EXPO, workspace))
        .isEqualTo(PreviewMode.MOBILE_EXPO_GO);
  }

  @Test
  @DisplayName("routes Node MCP projects to inspector")
  void detectsNodeMcp(@TempDir Path workspace) throws IOException {
    Files.writeString(
        workspace.resolve("package.json"),
        """
        {"dependencies":{"@modelcontextprotocol/sdk":"^1.0.0"}}
        """);

    assertThat(PreviewModeDetector.detect(PreviewProjectType.NODE_BACKEND, workspace))
        .isEqualTo(PreviewMode.MCP_INSPECTOR);
  }

  @Test
  @DisplayName("routes Python web frameworks to REST instead of terminal")
  void detectsPythonRest(@TempDir Path workspace) throws IOException {
    Files.writeString(workspace.resolve("requirements.txt"), "fastapi\nuvicorn\n");

    assertThat(PreviewModeDetector.detect(PreviewProjectType.PYTHON, workspace))
        .isEqualTo(PreviewMode.REST);
  }

  @Test
  @DisplayName("routes Python scripts to terminal")
  void detectsPythonCli(@TempDir Path workspace) throws IOException {
    Files.writeString(
        workspace.resolve("main.py"),
        """
        if __name__ == "__main__":
            print("hello")
        """);

    assertThat(PreviewModeDetector.detect(PreviewProjectType.PYTHON, workspace))
        .isEqualTo(PreviewMode.CLI_TERMINAL);
  }

  @Test
  @DisplayName("routes Go HTTP services to REST")
  void detectsGoRest(@TempDir Path workspace) throws IOException {
    Files.writeString(workspace.resolve("go.mod"), "module example.com/app\n");
    Files.writeString(
        workspace.resolve("main.go"),
        """
        package main
        import "net/http"
        func main() { http.ListenAndServe(":8080", nil) }
        """);

    assertThat(PreviewModeDetector.detect(PreviewProjectType.GO, workspace))
        .isEqualTo(PreviewMode.REST);
  }

  @Test
  @DisplayName("routes static and frontend projects to full-stack web preview")
  void detectsWebPreview(@TempDir Path workspace) {
    assertThat(PreviewModeDetector.detect(PreviewProjectType.STATIC_HTML, workspace))
        .isEqualTo(PreviewMode.FULL_STACK);
    assertThat(PreviewModeDetector.detect(PreviewProjectType.NODE_FRONTEND, workspace))
        .isEqualTo(PreviewMode.FULL_STACK);
  }
}
