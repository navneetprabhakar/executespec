package com.navneet.executespec.deployment.strategy;

import com.navneet.executespec.deployment.DeploymentProperties;
import com.navneet.executespec.state.entity.Deployment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Deployment strategy for PHP projects (Laravel, Symfony, plain PHP).
 *
 * <p>Detection signal: {@code composer.json} present, no {@code pom.xml}.
 *
 * <p>Entry-command resolution at Dockerfile-generation time:
 * <ol>
 *   <li>Laravel  — {@code artisan} file present
 *       → {@code php artisan serve --host=0.0.0.0 --port=8080}</li>
 *   <li>Generic  — PHP built-in server rooted at {@code public/} if it exists,
 *       otherwise at the workspace root</li>
 * </ol>
 *
 * <p>All containers expose port 8080 for consistency with the other strategies.
 */
@Component
@Order(7)
@Slf4j
public class PhpDeploymentStrategy implements DeploymentStrategy {

    @Override
    public Deployment.DeployStrategy type() {
        return Deployment.DeployStrategy.PHP;
    }

    @Override
    public boolean applies(Path workspacePath) {
        if (Files.exists(workspacePath.resolve("pom.xml"))) return false;
        return Files.exists(workspacePath.resolve("composer.json"));
    }

    @Override
    public String generateDockerfile(Path workspacePath, DeploymentProperties props) {
        String cmd = resolveEntryCommand(workspacePath);
        log.info("[PhpStrategy] Resolved entry command: {}", cmd);

        return """
                FROM php:8.3-cli
                RUN apt-get update && apt-get install -y --no-install-recommends \\
                    libpq-dev libzip-dev zip unzip git curl && \\
                    docker-php-ext-install pdo pdo_mysql pdo_pgsql zip opcache && \\
                    rm -rf /var/lib/apt/lists/*

                COPY --from=composer:2 /usr/bin/composer /usr/bin/composer

                WORKDIR /app
                COPY . .
                RUN if [ -f composer.json ]; then \\
                    composer install --no-dev --optimize-autoloader --no-interaction 2>/dev/null || true; \\
                    fi

                EXPOSE 8080
                ENV PORT=8080
                ENV APP_ENV=production
                CMD [%s]
                """.formatted(cmd);
    }

    @Override
    public String detectionSummary(Path workspacePath) {
        return "PHP project detected (%s)".formatted(detectFramework(workspacePath));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveEntryCommand(Path workspacePath) {
        // Laravel: artisan CLI present
        if (Files.exists(workspacePath.resolve("artisan"))) {
            return "\"php\", \"artisan\", \"serve\", \"--host=0.0.0.0\", \"--port=8080\"";
        }
        // Symfony or other framework with a public/ directory
        if (Files.exists(workspacePath.resolve("public/index.php"))) {
            return "\"php\", \"-S\", \"0.0.0.0:8080\", \"-t\", \"public\"";
        }
        // Plain PHP: index.php at root
        if (Files.exists(workspacePath.resolve("index.php"))) {
            return "\"php\", \"-S\", \"0.0.0.0:8080\"";
        }
        // Fallback: serve from public/ (most frameworks put entry there)
        return "\"php\", \"-S\", \"0.0.0.0:8080\", \"-t\", \"public\"";
    }

    private String detectFramework(Path workspacePath) {
        if (Files.exists(workspacePath.resolve("artisan")))            return "Laravel";
        if (Files.exists(workspacePath.resolve("bin/console")))        return "Symfony";
        if (Files.exists(workspacePath.resolve("public/index.php")))   return "PHP (public/)";
        return "PHP";
    }
}
