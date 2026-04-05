package com.navneet.executespec.deployment.analyzer;

import com.navneet.executespec.deployment.preview.PreviewProjectType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects the health check endpoint path for an application.
 * Falls back to sensible defaults per project type.
 */
@Component
@Slf4j
public class HealthCheckDetector {

    /**
     * Detect health check path, or return a sensible default for the project type.
     */
    public String detect(Path workspace, PreviewProjectType projectType) {
        if (workspace == null) return defaultForType(projectType);

        // Spring Boot: check for actuator
        if (projectType == PreviewProjectType.SPRING_BOOT) {
            return detectSpringBoot(workspace);
        }

        // Node/Python: check for common health endpoints
        if (projectType == PreviewProjectType.NODE_BACKEND || projectType == PreviewProjectType.PYTHON) {
            return detectGenericBackend(workspace);
        }

        // Frontend/static: just check root
        return "/";
    }

    private String detectSpringBoot(Path workspace) {
        // Check if actuator is in dependencies
        String pom = readSafe(workspace.resolve("pom.xml"));
        if (pom != null && pom.contains("spring-boot-starter-actuator")) {
            // Check if custom base path is configured
            for (String cfg : new String[]{
                    "src/main/resources/application.yml",
                    "src/main/resources/application.yaml"}) {
                String content = readSafe(workspace.resolve(cfg));
                if (content != null && content.contains("management.endpoints.web.base-path")) {
                    // Custom path — hard to parse YAML reliably, stick with default
                    log.debug("Custom actuator base path detected, using /actuator/health");
                }
            }
            return "/actuator/health";
        }

        String gradle = readSafe(workspace.resolve("build.gradle"));
        if (gradle == null) gradle = readSafe(workspace.resolve("build.gradle.kts"));
        if (gradle != null && gradle.contains("spring-boot-starter-actuator")) {
            return "/actuator/health";
        }

        // No actuator — try common patterns
        return "/actuator/health"; // Spring Boot default with actuator is so common, assume it
    }

    private String detectGenericBackend(Path workspace) {
        // Scan source files for common health endpoint patterns
        String pkg = readSafe(workspace.resolve("package.json"));
        if (pkg != null) {
            // Most Express/Fastify apps have /health or /api/health
            // Check main entry points
            for (String entry : new String[]{"src/index.ts", "src/index.js", "src/app.ts", "src/app.js",
                    "src/server.ts", "src/server.js", "index.ts", "index.js", "app.ts", "app.js"}) {
                String content = readSafe(workspace.resolve(entry));
                if (content != null) {
                    if (content.contains("/health")) return "/health";
                    if (content.contains("/api/health")) return "/api/health";
                    if (content.contains("/healthz")) return "/healthz";
                }
            }
        }
        return "/health";
    }

    private String defaultForType(PreviewProjectType type) {
        if (type == null) return "/";
        return switch (type) {
            case SPRING_BOOT -> "/actuator/health";
            case NODE_BACKEND, PYTHON, GO, RUST, JAVA -> "/health";
            case NODE_FRONTEND, FULL_STACK, STATIC_HTML -> "/";
        };
    }

    private String readSafe(Path file) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            String content = Files.readString(file);
            return content.length() > 50_000 ? content.substring(0, 50_000) : content;
        } catch (IOException e) {
            return null;
        }
    }
}
