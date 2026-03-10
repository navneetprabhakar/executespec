package com.navneet.executespec.deployment.strategy;

import com.navneet.executespec.deployment.DeploymentProperties;
import com.navneet.executespec.state.entity.Deployment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Deployment strategy for Ruby projects (Rails, Sinatra, generic Rack).
 *
 * <p>Detection signal: {@code Gemfile} present, no {@code pom.xml}.
 *
 * <p>Entry-command resolution at Dockerfile-generation time:
 * <ol>
 *   <li>Rails  — {@code config/application.rb} present
 *       → {@code bundle exec rails server -b 0.0.0.0 -p 8080}</li>
 *   <li>Rack   — {@code config.ru} present
 *       → {@code bundle exec rackup --host 0.0.0.0 --port 8080}</li>
 *   <li>Generic → {@code bundle exec ruby app.rb} or {@code main.rb}</li>
 * </ol>
 */
@Component
@Order(6)
@Slf4j
public class RubyDeploymentStrategy implements DeploymentStrategy {

    @Override
    public Deployment.DeployStrategy type() {
        return Deployment.DeployStrategy.RUBY;
    }

    @Override
    public boolean applies(Path workspacePath) {
        if (Files.exists(workspacePath.resolve("pom.xml"))) return false;
        return Files.exists(workspacePath.resolve("Gemfile"));
    }

    @Override
    public String generateDockerfile(Path workspacePath, DeploymentProperties props) {
        String cmd = resolveEntryCommand(workspacePath);
        log.info("[RubyStrategy] Resolved entry command: {}", cmd);

        return """
                FROM ruby:3.3-slim
                RUN apt-get update && apt-get install -y --no-install-recommends \\
                    build-essential libpq-dev nodejs npm curl && \\
                    rm -rf /var/lib/apt/lists/*

                WORKDIR /app
                COPY Gemfile Gemfile.lock* ./
                RUN bundle install --without development test

                COPY . .
                EXPOSE 8080
                ENV PORT=8080
                ENV RAILS_ENV=production
                CMD [%s]
                """.formatted(cmd);
    }

    @Override
    public String detectionSummary(Path workspacePath) {
        return "Ruby project detected (%s)".formatted(detectFramework(workspacePath));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveEntryCommand(Path workspacePath) {
        // Rails: config/application.rb is the canonical marker
        if (Files.exists(workspacePath.resolve("config/application.rb"))) {
            return "\"bundle\", \"exec\", \"rails\", \"server\", \"-b\", \"0.0.0.0\", \"-p\", \"8080\"";
        }
        // Rack: config.ru (Sinatra, Hanami, plain Rack)
        if (Files.exists(workspacePath.resolve("config.ru"))) {
            return "\"bundle\", \"exec\", \"rackup\", \"--host\", \"0.0.0.0\", \"--port\", \"8080\"";
        }
        // Generic Ruby script
        if (Files.exists(workspacePath.resolve("app.rb"))) {
            return "\"bundle\", \"exec\", \"ruby\", \"app.rb\"";
        }
        if (Files.exists(workspacePath.resolve("main.rb"))) {
            return "\"bundle\", \"exec\", \"ruby\", \"main.rb\"";
        }
        return "\"bundle\", \"exec\", \"ruby\", \"app.rb\"";
    }

    private String detectFramework(Path workspacePath) {
        if (Files.exists(workspacePath.resolve("config/application.rb"))) return "Rails";
        if (Files.exists(workspacePath.resolve("config.ru"))) {
            try {
                String rackup = Files.readString(workspacePath.resolve("config.ru")).toLowerCase();
                if (rackup.contains("sinatra")) return "Sinatra";
            } catch (IOException ignored) {}
            return "Rack";
        }
        return "Ruby";
    }
}
