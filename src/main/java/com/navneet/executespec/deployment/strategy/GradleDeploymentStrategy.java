package com.navneet.executespec.deployment.strategy;

import java.nio.file.Files;
import java.nio.file.Path;

import com.navneet.executespec.deployment.DeploymentProperties;
import com.navneet.executespec.state.entity.Deployment;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Deployment strategy for Gradle-based JVM projects (Spring Boot/Gradle, Micronaut, Quarkus, plain
 * Kotlin/Java, Android backend services, etc.).
 *
 * <p>Detection signals: {@code build.gradle} or {@code build.gradle.kts} present, no {@code
 * pom.xml} (Maven takes precedence on mixed workspaces).
 *
 * <p>Two-stage Dockerfile:
 *
 * <ul>
 *   <li>Stage 1 ({@code gradle:8-jdk21}): {@code gradle build -x test --no-daemon}
 *   <li>Stage 2 ({@code eclipse-temurin:21-jre-alpine}): run the assembled jar
 * </ul>
 */
@Component
@Order(2)
@Slf4j
public class GradleDeploymentStrategy implements DeploymentStrategy {

  @Override
  public Deployment.DeployStrategy type() {
    return Deployment.DeployStrategy.GRADLE;
  }

  @Override
  public boolean applies(Path workspacePath) {
    if (Files.exists(workspacePath.resolve("pom.xml"))) return false;
    return Files.exists(workspacePath.resolve("build.gradle"))
        || Files.exists(workspacePath.resolve("build.gradle.kts"));
  }

  @Override
  public String generateDockerfile(Path workspacePath, DeploymentProperties props) {
    boolean isKotlin = Files.exists(workspacePath.resolve("build.gradle.kts"));
    log.info("[GradleStrategy] Kotlin DSL: {}", isKotlin);

    return """
           # Stage 1: Build
           FROM gradle:8-jdk21 AS build
           WORKDIR /app
           COPY . .
           RUN gradle build -x test --no-daemon

           # Stage 2: Runtime
           FROM eclipse-temurin:21-jre-alpine
           WORKDIR /app
           # Copy whichever jar was produced (typically in build/libs/)
           COPY --from=build /app/build/libs/*.jar app.jar
           EXPOSE 8080
           ENV PORT=8080
           ENV JAVA_OPTS="-XX:+UseSerialGC -Xmx256m"
           ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --server.port=${PORT}"]
           """;
  }

  @Override
  public String detectionSummary(Path workspacePath) {
    boolean isKotlin = Files.exists(workspacePath.resolve("build.gradle.kts"));
    return "Gradle project detected (%s DSL)".formatted(isKotlin ? "Kotlin" : "Groovy");
  }
}
