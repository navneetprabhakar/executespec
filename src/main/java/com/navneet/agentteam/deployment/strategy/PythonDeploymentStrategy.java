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
 * Deployment strategy for Python projects.
 *
 * <p>Detection signals (any one is sufficient, pom.xml must be absent):
 * <ul>
 *   <li>{@code requirements.txt}</li>
 *   <li>{@code setup.py}</li>
 *   <li>{@code pyproject.toml}</li>
 *   <li>{@code Pipfile}</li>
 * </ul>
 *
 * <p>Entry-command resolution at Dockerfile-generation time:
 * <ol>
 *   <li>Django  → {@code manage.py runserver 0.0.0.0:8080}</li>
 *   <li>FastAPI/uvicorn → {@code uvicorn <module>:app --host 0.0.0.0 --port 8080}</li>
 *   <li>Flask/generic → {@code python app.py} or {@code python main.py}</li>
 * </ol>
 *
 * <p>All containers expose port 8080 to stay consistent with the Spring Boot strategy.
 * The generated app must bind to {@code 0.0.0.0:8080} (or read {@code $PORT}).
 */
@Component
@Order(4)
@Slf4j
public class PythonDeploymentStrategy implements DeploymentStrategy {

    @Override
    public Deployment.DeployStrategy type() {
        return Deployment.DeployStrategy.PYTHON;
    }

    /**
     * Applies when at least one Python project marker exists and there is no {@code pom.xml}
     * (Spring Boot takes precedence on mixed Java/Python workspaces).
     */
    @Override
    public boolean applies(Path workspacePath) {
        if (Files.exists(workspacePath.resolve("pom.xml"))) return false;
        return Files.exists(workspacePath.resolve("requirements.txt"))
            || Files.exists(workspacePath.resolve("setup.py"))
            || Files.exists(workspacePath.resolve("pyproject.toml"))
            || Files.exists(workspacePath.resolve("Pipfile"));
    }

    @Override
    public String generateDockerfile(Path workspacePath, DeploymentProperties props) {
        String cmd = resolveEntryCommand(workspacePath);
        log.info("[PythonStrategy] Resolved entry command: {}", cmd);

        return """
                FROM python:3.11-slim
                WORKDIR /app

                # Copy dependency manifests first for layer-cache efficiency
                COPY . .

                # Install dependencies (supports requirements.txt, pyproject.toml, or Pipfile)
                RUN if [ -f requirements.txt ]; then \\
                        pip install --no-cache-dir -r requirements.txt; \\
                    elif [ -f pyproject.toml ]; then \\
                        pip install --no-cache-dir .; \\
                    elif [ -f Pipfile ]; then \\
                        pip install --no-cache-dir pipenv && pipenv install --system --deploy; \\
                    fi

                EXPOSE 8080
                ENV PORT=8080

                CMD [%s]
                """.formatted(cmd);
    }

    @Override
    public String detectionSummary(Path workspacePath) {
        String framework = detectFramework(workspacePath);
        return "Python project detected (%s)".formatted(framework);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolve the Docker CMD exec-form string at Dockerfile generation time so the
     * correct entry point is baked in without needing a shell wrapper at runtime.
     */
    private String resolveEntryCommand(Path workspacePath) {
        // Django: manage.py present
        if (Files.exists(workspacePath.resolve("manage.py"))) {
            return "\"python\", \"manage.py\", \"runserver\", \"0.0.0.0:8080\"";
        }

        // FastAPI / uvicorn: requirements.txt lists uvicorn or fastapi
        if (requiresUvicorn(workspacePath)) {
            String module = Files.exists(workspacePath.resolve("main.py")) ? "main" : "app";
            return "\"uvicorn\", \"%s:app\", \"--host\", \"0.0.0.0\", \"--port\", \"8080\""
                    .formatted(module);
        }

        // Flask / generic: prefer app.py then main.py
        if (Files.exists(workspacePath.resolve("app.py"))) {
            return "\"python\", \"app.py\"";
        }
        if (Files.exists(workspacePath.resolve("main.py"))) {
            return "\"python\", \"main.py\"";
        }

        // Sensible fallback — matches what most generated projects will have
        return "\"python\", \"app.py\"";
    }

    private boolean requiresUvicorn(Path workspacePath) {
        Path req = workspacePath.resolve("requirements.txt");
        if (!Files.exists(req)) return false;
        try {
            String content = Files.readString(req).toLowerCase();
            return content.contains("fastapi") || content.contains("uvicorn");
        } catch (IOException e) {
            return false;
        }
    }

    private String detectFramework(Path workspacePath) {
        if (Files.exists(workspacePath.resolve("manage.py")))  return "Django";
        if (requiresUvicorn(workspacePath))                    return "FastAPI/uvicorn";
        try {
            Path req = workspacePath.resolve("requirements.txt");
            if (Files.exists(req)) {
                String content = Files.readString(req).toLowerCase();
                if (content.contains("flask"))  return "Flask";
                if (content.contains("aiohttp")) return "aiohttp";
                if (content.contains("tornado")) return "Tornado";
            }
        } catch (IOException ignored) {}
        return "Python";
    }
}
