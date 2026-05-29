package com.navneet.executespec.deployment.strategy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.navneet.executespec.deployment.DeploymentProperties;
import com.navneet.executespec.state.entity.Deployment;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Deployment strategy for Rust projects (Axum, Actix-Web, Warp, Rocket, etc.).
 *
 * <p>Detection signal: {@code Cargo.toml} present.
 *
 * <p>Two-stage Dockerfile:
 *
 * <ul>
 *   <li>Stage 1 ({@code rust:1.77-slim}): {@code cargo build --release}
 *   <li>Stage 2 ({@code debian:bookworm-slim}): minimal runtime with the compiled binary
 * </ul>
 *
 * <p>The binary name is read from {@code [package] name} in {@code Cargo.toml} at
 * Dockerfile-generation time so no shell glob is needed at container startup.
 */
@Component
@Order(8)
@Slf4j
public class RustDeploymentStrategy implements DeploymentStrategy {

  @Override
  public Deployment.DeployStrategy type() {
    return Deployment.DeployStrategy.RUST;
  }

  @Override
  public boolean applies(Path workspacePath) {
    return Files.exists(workspacePath.resolve("Cargo.toml"));
  }

  @Override
  public String generateDockerfile(Path workspacePath, DeploymentProperties props) {
    String binaryName = detectBinaryName(workspacePath);
    log.info("[RustStrategy] Detected binary name: {}", binaryName);

    return """
           # Stage 1: Build
           FROM rust:1.77-slim AS build
           RUN apt-get update && apt-get install -y --no-install-recommends \\
               pkg-config libssl-dev ca-certificates && \\
               rm -rf /var/lib/apt/lists/*
           WORKDIR /app
           COPY . .
           RUN cargo build --release

           # Stage 2: Minimal runtime
           FROM debian:bookworm-slim
           RUN apt-get update && apt-get install -y --no-install-recommends \\
               ca-certificates && \\
               rm -rf /var/lib/apt/lists/*
           WORKDIR /app
           COPY --from=build /app/target/release/%s ./server
           EXPOSE 8080
           ENV PORT=8080
           ENTRYPOINT ["./server"]
           """
        .formatted(binaryName);
  }

  @Override
  public String detectionSummary(Path workspacePath) {
    return "Rust project detected (Cargo.toml found, binary: %s)"
        .formatted(detectBinaryName(workspacePath));
  }

  /** Read the binary/package name from [package] name = "..." in Cargo.toml. */
  private String detectBinaryName(Path workspacePath) {
    try {
      String content = Files.readString(workspacePath.resolve("Cargo.toml"));
      for (String line : content.lines().toList()) {
        String stripped = line.strip();
        if (stripped.startsWith("name") && stripped.contains("=")) {
          String value = stripped.split("=", 2)[1].strip();
          // Remove surrounding quotes
          return value.replace("\"", "").replace("'", "").strip();
        }
      }
    } catch (IOException ignored) {
    }
    return "app";
  }
}
