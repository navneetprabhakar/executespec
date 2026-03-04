package com.navneet.agentteam.deployment.strategy;

import com.navneet.agentteam.deployment.DeploymentProperties;
import com.navneet.agentteam.state.entity.Deployment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Deployment strategy for full-stack projects with a {@code backend/} + {@code frontend/}
 * monorepo layout (the pattern ExecuteSpec agents naturally produce for full-stack tasks).
 *
 * <p>Detection signals (both must be present):
 * <ul>
 *   <li>{@code backend/pom.xml} (Maven) <b>or</b> {@code backend/build.gradle[.kts]} (Gradle)</li>
 *   <li>{@code frontend/package.json}</li>
 * </ul>
 *
 * <p>Three-stage Dockerfile:
 * <ol>
 *   <li>Build frontend ({@code node:20-slim}) → produces {@code dist/} or {@code build/}</li>
 *   <li>Build backend ({@code maven:3.9-eclipse-temurin-21} or {@code gradle:8-jdk21}),
 *       copying the frontend output into {@code src/main/resources/static/} so Spring Boot
 *       serves it at the root path.</li>
 *   <li>Slim runtime ({@code eclipse-temurin:21-jre-alpine}), runs the fat JAR.</li>
 * </ol>
 *
 * <p>This strategy has {@code @Order(0)} — highest priority — so it is checked before the
 * individual {@code SpringBootDeploymentStrategy} and {@code NodeDeploymentStrategy} which
 * both look for root-level signals and would otherwise skip a monorepo workspace entirely.
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
        boolean hasBackend = Files.exists(workspacePath.resolve("backend/pom.xml"))
            || Files.exists(workspacePath.resolve("backend/build.gradle"))
            || Files.exists(workspacePath.resolve("backend/build.gradle.kts"));
        boolean hasFrontend = Files.exists(workspacePath.resolve("frontend/package.json"));
        return hasBackend && hasFrontend;
    }

    @Override
    public String generateDockerfile(Path workspacePath, DeploymentProperties props) {
        boolean isMaven = Files.exists(workspacePath.resolve("backend/pom.xml"));
        String frontendDist = detectFrontendDist(workspacePath);
        log.info("[FullStackStrategy] backend={}, frontendDist={}",
            isMaven ? "Maven" : "Gradle", frontendDist);

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
                    """.formatted(frontendDist);
        } else {
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
                    """.formatted(frontendDist);
        }
    }

    @Override
    public String detectionSummary(Path workspacePath) {
        boolean isMaven = Files.exists(workspacePath.resolve("backend/pom.xml"));
        String frontendDist = detectFrontendDist(workspacePath);
        return "Full-stack project detected (backend: %s, frontend: package.json → %s)"
            .formatted(isMaven ? "Maven/Spring Boot" : "Gradle/Spring Boot", frontendDist);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Detects the frontend build output directory.
     * <ul>
     *   <li>Vite → {@code dist/} (default)</li>
     *   <li>Create React App ({@code react-scripts}) → {@code build/}</li>
     *   <li>Falls back to whichever directory already exists, then to {@code dist/}</li>
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
        } catch (IOException ignored) {}

        // Fall back to whichever output directory already exists in the workspace
        if (Files.isDirectory(workspacePath.resolve("frontend/dist"))) {
            return "dist/";
        }
        if (Files.isDirectory(workspacePath.resolve("frontend/build"))) {
            return "build/";
        }

        return "dist/";  // safest default — Vite is the dominant build tool
    }
}
