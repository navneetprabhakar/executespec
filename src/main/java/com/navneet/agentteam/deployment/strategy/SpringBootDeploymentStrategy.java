package com.navneet.agentteam.deployment.strategy;

import com.navneet.agentteam.deployment.DeploymentProperties;
import com.navneet.agentteam.state.entity.Deployment;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class SpringBootDeploymentStrategy implements DeploymentStrategy {

    @Override
    public Deployment.DeployStrategy type() {
        return Deployment.DeployStrategy.SPRING_BOOT;
    }

    @Override
    public boolean applies(Path workspacePath) {
        return Files.exists(workspacePath.resolve("pom.xml"));
    }

    @Override
    public String generateDockerfile(Path workspacePath, DeploymentProperties props) {
        String baseImage = props.springBoot().baseImage();
        return """
            # Stage 1: Build
            FROM maven:3.9-eclipse-temurin-21 AS build
            WORKDIR /app
            COPY pom.xml .
            RUN mvn dependency:go-offline -B
            COPY src ./src
            RUN mvn package -DskipTests -B

            # Stage 2: Runtime
            FROM %s
            WORKDIR /app
            COPY --from=build /app/target/*.jar app.jar
            EXPOSE 8080
            ENV PORT=8080
            ENV SPRING_PROFILES_ACTIVE=cloud
            ENV JAVA_OPTS="-XX:+UseSerialGC -Xmx256m"
            ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --server.port=${PORT}"]
            """.formatted(baseImage);
    }

    @Override
    public String detectionSummary(Path workspacePath) {
        return "Spring Boot project detected (pom.xml found)";
    }
}
