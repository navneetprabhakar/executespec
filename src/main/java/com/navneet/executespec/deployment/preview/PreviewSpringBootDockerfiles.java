package com.navneet.executespec.deployment.preview;

import java.nio.file.Files;
import java.nio.file.Path;

final class PreviewSpringBootDockerfiles {

  private PreviewSpringBootDockerfiles() {}

  static String cloudRun(Path workspace) {
    boolean gradle = isGradleProject(workspace);

    var sb = new StringBuilder();
    sb.append("FROM eclipse-temurin:21\n");
    sb.append("WORKDIR /app\n");
    sb.append("LABEL preview=true\n");
    sb.append("ENV PORT=8080\n");
    sb.append("ENV SERVER_ADDRESS=0.0.0.0\n");

    if (gradle) {
      sb.append("COPY . .\n");
      sb.append("RUN chmod +x gradlew 2>/dev/null || true\n");
      sb.append("EXPOSE 8080\n");
      sb.append(PreviewDockerfileSnippets.cloudRunNonRootUser(false));
      sb.append("CMD sh -c \"SERVER_PORT=${PORT:-8080} ");
      sb.append(
          "$(if [ -f gradlew ]; then echo './gradlew bootRun --no-daemon'; else echo 'gradle"
              + " bootRun --no-daemon'; fi)\"\n");
    } else {
      sb.append("RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*\n");
      sb.append("COPY pom.xml .\n");
      sb.append("RUN mvn dependency:go-offline -q -B || true\n");
      sb.append("COPY . .\n");
      sb.append("RUN mvn compile -DskipTests -q -B\n");
      sb.append("EXPOSE 8080\n");
      sb.append(PreviewDockerfileSnippets.cloudRunNonRootUser(false));
      sb.append(
          "CMD SERVER_PORT=${PORT:-8080} mvn -Dmaven.repo.local=/app/.m2 spring-boot:run"
              + " -DskipTests -q\n");
    }
    return sb.toString();
  }

  static String dockerfile(Path workspace) {
    boolean inBackendSubdir = isBackendSubdirProject(workspace);
    Path projectDir = inBackendSubdir ? workspace.resolve("backend") : workspace;
    boolean gradle = isGradleProject(projectDir);

    if (inBackendSubdir) {
      return gradle ? backendGradleDockerfile() : backendMavenDockerfile();
    }
    return gradle ? rootGradleDockerfile() : rootMavenDockerfile();
  }

  private static boolean isGradleProject(Path workspace) {
    return Files.exists(workspace.resolve("build.gradle"))
        || Files.exists(workspace.resolve("build.gradle.kts"));
  }

  private static boolean isBackendSubdirProject(Path workspace) {
    return !Files.exists(workspace.resolve("pom.xml"))
        && !isGradleProject(workspace)
        && (Files.exists(workspace.resolve("backend/pom.xml"))
            || Files.exists(workspace.resolve("backend/build.gradle"))
            || Files.exists(workspace.resolve("backend/build.gradle.kts")));
  }

  private static String backendGradleDockerfile() {
    return """
FROM eclipse-temurin:21
WORKDIR /app
LABEL preview=true
ENV SERVER_ADDRESS=0.0.0.0
ENV SERVER_PORT=8080
COPY . .
WORKDIR /app/backend
RUN chmod +x gradlew 2>/dev/null || true
EXPOSE 8080
CMD ["sh", "-c", "if [ -f gradlew ]; then ./gradlew bootRun --no-daemon; else gradle bootRun --no-daemon; fi"]
""";
  }

  private static String backendMavenDockerfile() {
    return """
           FROM eclipse-temurin:21
           WORKDIR /app
           LABEL preview=true
           ENV SERVER_ADDRESS=0.0.0.0
           ENV SERVER_PORT=8080
           RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*
           COPY backend/pom.xml ./backend/pom.xml
           RUN cd backend && mvn dependency:go-offline -q -B || true
           COPY . .
           RUN cd backend && mvn compile -DskipTests -q -B
           EXPOSE 8080
           WORKDIR /app/backend
           CMD ["mvn", "spring-boot:run", "-DskipTests", "-q"]
           """;
  }

  private static String rootGradleDockerfile() {
    return """
FROM eclipse-temurin:21
WORKDIR /app
LABEL preview=true
ENV SERVER_ADDRESS=0.0.0.0
ENV SERVER_PORT=8080
COPY . .
RUN chmod +x gradlew 2>/dev/null || true
EXPOSE 8080
CMD ["sh", "-c", "if [ -f gradlew ]; then ./gradlew bootRun --no-daemon; else gradle bootRun --no-daemon; fi"]
""";
  }

  private static String rootMavenDockerfile() {
    return """
           FROM eclipse-temurin:21
           WORKDIR /app
           LABEL preview=true
           ENV SERVER_ADDRESS=0.0.0.0
           ENV SERVER_PORT=8080
           RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*
           COPY pom.xml .
           RUN mvn dependency:go-offline -q -B || true
           COPY . .
           RUN mvn compile -DskipTests -q -B
           EXPOSE 8080
           CMD ["mvn", "spring-boot:run", "-DskipTests", "-q"]
           """;
  }
}
