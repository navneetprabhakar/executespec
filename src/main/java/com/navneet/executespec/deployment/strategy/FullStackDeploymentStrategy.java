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
 * Deployment strategy for full-stack projects with a {@code backend/} + {@code frontend/} monorepo
 * layout (the pattern ExecuteSpec agents naturally produce for full-stack tasks).
 *
 * <p>Detection signals (both must be present):
 *
 * <ul>
 *   <li>Java backend: {@code backend/pom.xml} (Maven) <b>or</b> {@code backend/build.gradle[.kts]}
 *       (Gradle)
 *   <li>Node.js backend: {@code backend/package.json} (Express/Fastify/NestJS etc.)
 *   <li>Frontend: {@code frontend/package.json} (Vite/CRA/Angular/Vue)
 * </ul>
 *
 * <p>Dockerfile variants:
 *
 * <ul>
 *   <li><b>Java backend (Maven/Gradle)</b>: 3-stage — node:20-slim → maven/gradle builder →
 *       eclipse-temurin runtime. Frontend {@code dist/} is embedded in Spring Boot's {@code
 *       src/main/resources/static/}.
 *   <li><b>Node.js backend</b>: 2-stage — node:20-slim builds frontend, then a combined image runs
 *       nginx (serves SPA at {@code /}) and the Node backend (proxied at {@code /api/}).
 * </ul>
 *
 * <p>This strategy has {@code @Order(0)} — highest priority — so it is checked before the
 * individual {@code SpringBootDeploymentStrategy} and {@code NodeDeploymentStrategy} which both
 * look for root-level signals and would otherwise skip a monorepo workspace entirely.
 */
@Component
@Order(0)
@Slf4j
public class FullStackDeploymentStrategy implements DeploymentStrategy {

  @Override
  public Deployment.DeployStrategy type() {
    return Deployment.DeployStrategy.FULLSTACK;
  }

  @Override
  public boolean applies(Path workspacePath) {
    boolean hasJavaBackend =
        Files.exists(workspacePath.resolve("backend/pom.xml"))
            || Files.exists(workspacePath.resolve("backend/build.gradle"))
            || Files.exists(workspacePath.resolve("backend/build.gradle.kts"));
    boolean hasNodeBackend = Files.exists(workspacePath.resolve("backend/package.json"));
    boolean hasFrontend = Files.exists(workspacePath.resolve("frontend/package.json"));
    return (hasJavaBackend || hasNodeBackend) && hasFrontend;
  }

  @Override
  public String generateDockerfile(Path workspacePath, DeploymentProperties props) {
    boolean isMaven = Files.exists(workspacePath.resolve("backend/pom.xml"));
    boolean isGradle =
        Files.exists(workspacePath.resolve("backend/build.gradle"))
            || Files.exists(workspacePath.resolve("backend/build.gradle.kts"));
    boolean isNodeBackend =
        !isMaven && !isGradle && Files.exists(workspacePath.resolve("backend/package.json"));
    String frontendDist = detectFrontendDist(workspacePath);

    log.info(
        "[FullStackStrategy] backend={}, frontendDist={}",
        isMaven ? "Maven" : isGradle ? "Gradle" : "Node.js",
        frontendDist);

    if (isMaven) {
      return """
             # ── Stage 1: Build frontend ───────────────────────────────────────────────
             FROM node:20-slim AS frontend-build
             WORKDIR /frontend
             COPY frontend/package*.json ./
             RUN npm ci --legacy-peer-deps || npm ci
             COPY frontend/ .
             RUN npm run build

             # ── Stage 2: Build backend (Maven) ────────────────────────────────────────
             FROM maven:3.9-eclipse-temurin-21 AS backend-build
             WORKDIR /app
             COPY backend/ .
             # Embed the compiled frontend into Spring Boot's static resources
             COPY --from=frontend-build /frontend/%s src/main/resources/static/
             RUN mvn package -q -DskipTests

             # ── Stage 3: Slim runtime ─────────────────────────────────────────────────
             FROM eclipse-temurin:21-jre-alpine
             WORKDIR /app
             COPY --from=backend-build /app/target/*.jar app.jar
             EXPOSE 8080
             ENV PORT=8080
             ENTRYPOINT ["java", "-jar", "app.jar"]
             """
          .formatted(frontendDist);
    } else if (isGradle) {
      return """
             # ── Stage 1: Build frontend ───────────────────────────────────────────────
             FROM node:20-slim AS frontend-build
             WORKDIR /frontend
             COPY frontend/package*.json ./
             RUN npm ci --legacy-peer-deps || npm ci
             COPY frontend/ .
             RUN npm run build

             # ── Stage 2: Build backend (Gradle) ───────────────────────────────────────
             FROM gradle:8-jdk21 AS backend-build
             WORKDIR /app
             COPY backend/ .
             # Embed the compiled frontend into Spring Boot's static resources
             COPY --from=frontend-build /frontend/%s src/main/resources/static/
             RUN gradle bootJar --no-daemon -q

             # ── Stage 3: Slim runtime ─────────────────────────────────────────────────
             FROM eclipse-temurin:21-jre-alpine
             WORKDIR /app
             COPY --from=backend-build /app/build/libs/*.jar app.jar
             EXPOSE 8080
             ENV PORT=8080
             ENTRYPOINT ["java", "-jar", "app.jar"]
             """
          .formatted(frontendDist);
    } else {
      // Node.js backend + React/Vite frontend
      // nginx serves the SPA at / and proxies /api/ calls to the Node backend on port 3001.
      return """
             # ── Stage 1: Build frontend ───────────────────────────────────────────────
             FROM node:20-slim AS frontend-build
             WORKDIR /frontend
             COPY frontend/package*.json ./
             RUN npm ci --legacy-peer-deps || npm ci
             COPY frontend/ .
             RUN npm run build

             # ── Stage 2: Node backend + nginx (serves SPA + proxies /api) ─────────────
             FROM node:20-slim
             RUN apt-get update && apt-get install -y --no-install-recommends nginx \\
                 && rm -rf /var/lib/apt/lists/*

             # Install backend dependencies
             WORKDIR /app
             COPY backend/package*.json ./
             RUN npm ci --omit=dev
             COPY backend/ .

             # Place built frontend where nginx can serve it
             COPY --from=frontend-build /frontend/%s /var/www/html

             # nginx: SPA at /, /api proxied to Node on port 3001
             RUN printf 'server {\\n\\
                 listen 8080;\\n\\
                 server_name _;\\n\\
                 root /var/www/html;\\n\\
                 index index.html;\\n\\
                 location / { try_files $uri $uri/ /index.html; }\\n\\
                 location /api/ {\\n\\
                     proxy_pass http://127.0.0.1:3001;\\n\\
                     proxy_http_version 1.1;\\n\\
                     proxy_set_header Host $host;\\n\\
                     proxy_set_header X-Real-IP $remote_addr;\\n\\
                     proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\\n\\
                 }\\n\\
             }\\n' > /etc/nginx/sites-available/default

             # Startup: nginx serves frontend; Node backend handles API
             RUN printf '#!/bin/sh\\nnginx -g "daemon off;" &\\nexec npm start\\n' \\
                 > /start.sh && chmod +x /start.sh

             EXPOSE 8080
             ENV NODE_ENV=production
             CMD ["/start.sh"]
             """
          .formatted(frontendDist);
    }
  }

  @Override
  public String detectionSummary(Path workspacePath) {
    boolean isMaven = Files.exists(workspacePath.resolve("backend/pom.xml"));
    boolean isGradle =
        Files.exists(workspacePath.resolve("backend/build.gradle"))
            || Files.exists(workspacePath.resolve("backend/build.gradle.kts"));
    String frontendDist = detectFrontendDist(workspacePath);
    String backendLabel =
        isMaven ? "Maven/Spring Boot" : isGradle ? "Gradle/Spring Boot" : "Node.js/Express";
    return "Full-stack project detected (backend: %s, frontend: package.json → %s)"
        .formatted(backendLabel, frontendDist);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  /**
   * Detects the frontend build output directory.
   *
   * <ul>
   *   <li>Vite → {@code dist/} (default)
   *   <li>Create React App ({@code react-scripts}) → {@code build/}
   *   <li>Falls back to whichever directory already exists, then to {@code dist/}
   * </ul>
   */
  private String detectFrontendDist(Path workspacePath) {
    Path pkgJson = workspacePath.resolve("frontend/package.json");
    try {
      String content = Files.readString(pkgJson).toLowerCase();
      // Vite projects declare "vite" as a dependency / devDependency
      if (content.contains("\"vite\"")) {
        return "dist/";
      }
      // Create React App uses react-scripts
      if (content.contains("react-scripts")) {
        return "build/";
      }
      // Angular / Vue CLI default to dist/
      if (content.contains("@angular/core") || content.contains("@vue/cli-service")) {
        return "dist/";
      }
    } catch (IOException ignored) {
    }

    // Fall back to whichever output directory already exists in the workspace
    if (Files.isDirectory(workspacePath.resolve("frontend/dist"))) {
      return "dist/";
    }
    if (Files.isDirectory(workspacePath.resolve("frontend/build"))) {
      return "build/";
    }

    return "dist/"; // safest default — Vite is the dominant build tool
  }
}
