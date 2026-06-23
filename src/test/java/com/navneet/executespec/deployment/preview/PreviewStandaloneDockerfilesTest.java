package com.navneet.executespec.deployment.preview;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("PreviewStandaloneDockerfiles")
class PreviewStandaloneDockerfilesTest {

  @Test
  @DisplayName("Cloud Run Go Dockerfile keeps port, non-root user, and server fallback")
  void cloudRunGo() {
    String dockerfile = PreviewStandaloneDockerfiles.cloudRunGo();

    assertThat(dockerfile).contains("FROM golang:1.22-alpine");
    assertThat(dockerfile).contains("ENV PORT=8080");
    assertThat(dockerfile).contains("USER app");
    assertThat(dockerfile).contains("if [ -f server ]; then ./server; else go run .; fi");
  }

  @Test
  @DisplayName("Cloud Run Rust Dockerfile keeps release binary fallback and non-root user")
  void cloudRunRust() {
    String dockerfile = PreviewStandaloneDockerfiles.cloudRunRust();

    assertThat(dockerfile).contains("FROM rust:1.77-slim");
    assertThat(dockerfile).contains("cargo build --release");
    assertThat(dockerfile).contains("USER app");
    assertThat(dockerfile).contains("target/release");
  }

  @Test
  @DisplayName("Cloud Run Java Dockerfile selects discovered main class")
  void cloudRunJavaUsesMainClass(@TempDir Path workspace) throws IOException {
    Files.writeString(
        workspace.resolve("DemoApp.java"),
        """
        public class DemoApp {
          public static void main(String[] args) {}
        }
        """);

    String dockerfile = PreviewStandaloneDockerfiles.cloudRunJava(workspace);

    assertThat(dockerfile).contains("RUN javac *.java");
    assertThat(dockerfile).contains("USER app");
    assertThat(dockerfile).contains("CMD java DemoApp");
  }

  @Test
  @DisplayName("Cloud Run static HTML Dockerfile runs nginx as nginx user with dynamic port")
  void cloudRunStaticHtml() {
    String dockerfile = PreviewStandaloneDockerfiles.cloudRunStaticHtml();

    assertThat(dockerfile).contains("FROM nginx:alpine");
    assertThat(dockerfile).contains("ENV PORT=8080");
    assertThat(dockerfile).contains("USER nginx");
    assertThat(dockerfile).contains("sed -i 's/listen 8080/listen '${PORT:-8080}'/'");
  }

  @Test
  @DisplayName("Expo Dockerfile keeps bundle export and Hermes guard")
  void reactNativeExpo() {
    String dockerfile = PreviewStandaloneDockerfiles.reactNativeExpo();

    assertThat(dockerfile).contains("npx expo export --platform android");
    assertThat(dockerfile).contains("hermes-compiler");
    assertThat(dockerfile).contains("Mobile Preview");
    assertThat(dockerfile).contains("FROM nginx:alpine");
  }

  @Test
  @DisplayName("CLI Go and Rust Dockerfiles wrap binaries with ttyd")
  void cliDockerfiles() {
    assertThat(PreviewStandaloneDockerfiles.cliGo())
        .contains("RUN apk add --no-cache ttyd")
        .contains("ttyd --port 8080 --writable ./app");

    assertThat(PreviewStandaloneDockerfiles.cliRust())
        .contains("ttyd.x86_64")
        .contains("exec ttyd --port 8080 --writable");
  }
}
