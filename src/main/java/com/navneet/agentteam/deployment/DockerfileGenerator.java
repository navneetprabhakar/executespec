package com.navneet.agentteam.deployment;

import com.navneet.agentteam.deployment.strategy.DeploymentStrategy;
import com.navneet.agentteam.state.entity.Deployment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DockerfileGenerator {

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

        DeploymentStrategy matched = strategies.stream()
            .filter(s -> s.applies(workspacePath))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No deployment strategy matches workspace at " + workspacePath
                + ". Supported signals: pom.xml (Spring Boot), requirements.txt/pyproject.toml/Pipfile (Python), "
                + "go.mod (Go), Gemfile (Ruby), composer.json (PHP), index.html (static site)."));

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
        return strategies.stream()
            .filter(s -> s.applies(workspacePath))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Existing Dockerfile found but cannot determine project strategy for " + workspacePath));
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
