package com.navneet.executespec.deployment;

import com.navneet.executespec.deployment.strategy.DeploymentStrategy;
import com.navneet.executespec.state.entity.Deployment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.deployment.preview.PreviewProjectType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class DockerfileGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Maps planner-declared project_type → DeployStrategy for strategy lookup. */
    private static final Map<String, Deployment.DeployStrategy> PROJECT_TYPE_TO_DEPLOY = Map.ofEntries(
            Map.entry("SPRING_BOOT", Deployment.DeployStrategy.SPRING_BOOT),
            Map.entry("JAVA", Deployment.DeployStrategy.SPRING_BOOT),
            Map.entry("NODE_FRONTEND", Deployment.DeployStrategy.NODE),
            Map.entry("NODE_BACKEND", Deployment.DeployStrategy.NODE),
            Map.entry("PYTHON", Deployment.DeployStrategy.PYTHON),
            Map.entry("GO", Deployment.DeployStrategy.GO),
            Map.entry("RUST", Deployment.DeployStrategy.RUST),
            Map.entry("RUBY", Deployment.DeployStrategy.RUBY),
            Map.entry("PHP", Deployment.DeployStrategy.PHP),
            Map.entry("FULL_STACK", Deployment.DeployStrategy.FULLSTACK),
            Map.entry("STATIC_HTML", Deployment.DeployStrategy.STATIC)
    );

    private final List<DeploymentStrategy> strategies;
    private final DeploymentProperties props;

    /**
     * Detect strategy and write deployment files to workspace.
     * If Dockerfile already exists, use it as-is (respect manual edits).
     * Returns the matched strategy, or throws if none match.
     */
    public DeploymentStrategy detectAndGenerate(Path workspacePath) throws IOException {
        if (Files.exists(workspacePath.resolve("Dockerfile"))) {
            log.info("Existing Dockerfile found — using as-is (not regenerating)");
            return detectStrategyOnly(workspacePath);
        }

        // Check planner-declared project type first
        DeploymentStrategy fromPlan = resolveFromProjectJson(workspacePath);
        if (fromPlan != null) {
            log.info("[Deploy] Using planner-declared project type → strategy: {}", fromPlan.type());
        }

        DeploymentStrategy matched = (fromPlan != null) ? fromPlan : strategies.stream()
            .filter(s -> s.applies(workspacePath))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No deployment strategy matches workspace at " + workspacePath
                + ". Supported signals: backend/pom.xml + frontend/package.json (Full-stack), "
                + "pom.xml (Spring Boot), build.gradle (Gradle/Java/Kotlin), "
                + "package.json (Node.js/Express/Next.js/Vite), "
                + "requirements.txt/pyproject.toml/Pipfile (Python), go.mod (Go), "
                + "Gemfile (Ruby), composer.json (PHP), Cargo.toml (Rust), "
                + "*.csproj/*.sln (.NET), index.html (static site)."));

        log.info("Strategy: {}", matched.detectionSummary(workspacePath));

        // Write Dockerfile
        String dockerfile = matched.generateDockerfile(workspacePath, props);
        Files.writeString(workspacePath.resolve("Dockerfile"), dockerfile);

        // Write .dockerignore (always)
        writeDockerignore(workspacePath);

        // Write nginx.conf for static strategy
        if (matched.type() == Deployment.DeployStrategy.STATIC) {
            writeNginxConf(workspacePath);
        }

        log.info("Generated deployment files: Dockerfile, .dockerignore{}",
            matched.type() == Deployment.DeployStrategy.STATIC ? ", nginx.conf" : "");

        return matched;
    }

    private DeploymentStrategy detectStrategyOnly(Path workspacePath) {
        DeploymentStrategy fromPlan = resolveFromProjectJson(workspacePath);
        if (fromPlan != null) return fromPlan;
        return strategies.stream()
            .filter(s -> s.applies(workspacePath))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Existing Dockerfile found but cannot determine project strategy for " + workspacePath));
    }

    /**
     * Reads .agent-context/project.json and maps the planner-declared project_type
     * to the matching DeploymentStrategy bean. Returns null if not found or invalid.
     */
    private DeploymentStrategy resolveFromProjectJson(Path workspacePath) {
        Path projectJson = workspacePath.resolve(".agent-context/project.json");
        if (!Files.exists(projectJson)) return null;
        try {
            JsonNode node = MAPPER.readTree(projectJson.toFile());
            String declared = node.path("project_type").asText(null);
            if (declared == null || declared.isBlank() || "UNKNOWN".equals(declared)) return null;
            Deployment.DeployStrategy target = PROJECT_TYPE_TO_DEPLOY.get(declared);
            if (target == null) {
                log.warn("[Deploy] Unknown planner-declared project type '{}' — falling back", declared);
                return null;
            }
            return strategies.stream()
                    .filter(s -> s.type() == target)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.debug("[Deploy] Could not read project.json: {}", e.getMessage());
            return null;
        }
    }

    private void writeDockerignore(Path workspacePath) throws IOException {
        String content = """
            .git
            .agent-context
            *.md
            !README.md
            target/
            build/
            node_modules/
            .gradle/
            .idea/
            .vscode/
            *.class
            *.jar
            .env
            .env.*
            .env.local
            .env.production
            credentials.json
            service-account.json
            key.json
            keyfile.json
            secrets.yml
            secrets.yaml
            """;
        Files.writeString(workspacePath.resolve(".dockerignore"), content);
    }

    private void writeNginxConf(Path workspacePath) throws IOException {
        String conf = """
            server {
                listen 8080;
                server_name _;
                root /usr/share/nginx/html;
                index index.html;
                location / {
                    try_files $uri $uri/ /index.html;
                }
            }
            """;
        Files.writeString(workspacePath.resolve("nginx.conf"), conf);
    }
}
