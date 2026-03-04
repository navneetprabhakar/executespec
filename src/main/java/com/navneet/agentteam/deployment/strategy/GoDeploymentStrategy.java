package com.navneet.agentteam.deployment.strategy;

import com.navneet.agentteam.deployment.DeploymentProperties;
import com.navneet.agentteam.state.entity.Deployment;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Deployment strategy for Go (golang) projects.
 *
 * <p>Detection signal: {@code go.mod} present, no {@code pom.xml}.
 *
 * <p>Two-stage Dockerfile: {@code golang:1.22-alpine} build → {@code alpine:3.19} runtime.
 * The compiled binary is expected to bind to {@code 0.0.0.0:$PORT} (defaults to 8080).
 */
@Component
@Order(5)
public class GoDeploymentStrategy implements DeploymentStrategy {

    @Override
    public Deployment.DeployStrategy type() {
        return Deployment.DeployStrategy.GO;
    }

    @Override
    public boolean applies(Path workspacePath) {
        if (Files.exists(workspacePath.resolve("pom.xml"))) return false;
        return Files.exists(workspacePath.resolve("go.mod"));
    }

    @Override
    public String generateDockerfile(Path workspacePath, DeploymentProperties props) {
        return """
                # Stage 1: Build
                FROM golang:1.22-alpine AS build
                RUN apk --no-cache add ca-certificates git tzdata
                WORKDIR /app
                COPY go.mod go.sum* ./
                RUN go mod download
                COPY . .
                RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o server .

                # Stage 2: Minimal runtime
                FROM alpine:3.19
                RUN apk --no-cache add ca-certificates tzdata
                WORKDIR /app
                COPY --from=build /app/server .
                EXPOSE 8080
                ENV PORT=8080
                ENTRYPOINT ["./server"]
                """;
    }

    @Override
    public String detectionSummary(Path workspacePath) {
        return "Go project detected (go.mod found)";
    }
}
