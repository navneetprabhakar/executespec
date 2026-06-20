package com.navneet.executespec.deployment.preview;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreviewSpringBootDockerfilesTest {

  @Test
  void dockerfileUsesRootMavenProject(@TempDir Path workspace) throws IOException {
    Files.writeString(workspace.resolve("pom.xml"), "<project></project>\n");

    String dockerfile = PreviewSpringBootDockerfiles.dockerfile(workspace);

    assertThat(dockerfile)
        .contains("COPY pom.xml .")
        .contains("mvn dependency:go-offline")
        .contains("CMD [\"mvn\", \"spring-boot:run\", \"-DskipTests\", \"-q\"]")
        .doesNotContain("WORKDIR /app/backend");
  }

  @Test
  void dockerfileUsesRootGradleProject(@TempDir Path workspace) throws IOException {
    Files.writeString(workspace.resolve("build.gradle"), "plugins { id 'java' }\n");

    String dockerfile = PreviewSpringBootDockerfiles.dockerfile(workspace);

    assertThat(dockerfile)
        .contains("RUN chmod +x gradlew")
        .contains("./gradlew bootRun --no-daemon")
        .contains("gradle bootRun --no-daemon")
        .doesNotContain("apt-get install -y maven");
  }

  @Test
  void dockerfileUsesBackendMavenProjectWhenRootHasNoBuildFiles(@TempDir Path workspace)
      throws IOException {
    Files.createDirectories(workspace.resolve("backend"));
    Files.writeString(workspace.resolve("backend/pom.xml"), "<project></project>\n");

    String dockerfile = PreviewSpringBootDockerfiles.dockerfile(workspace);

    assertThat(dockerfile)
        .contains("COPY backend/pom.xml ./backend/pom.xml")
        .contains("RUN cd backend && mvn dependency:go-offline")
        .contains("WORKDIR /app/backend");
  }

  @Test
  void dockerfileUsesBackendGradleProjectWhenRootHasNoBuildFiles(@TempDir Path workspace)
      throws IOException {
    Files.createDirectories(workspace.resolve("backend"));
    Files.writeString(workspace.resolve("backend/build.gradle.kts"), "plugins { java }\n");

    String dockerfile = PreviewSpringBootDockerfiles.dockerfile(workspace);

    assertThat(dockerfile)
        .contains("WORKDIR /app/backend")
        .contains("./gradlew bootRun --no-daemon")
        .doesNotContain("mvn dependency:go-offline");
  }

  @Test
  void cloudRunMavenAddsNonRootUserAndParameterizedSpringCommand(@TempDir Path workspace)
      throws IOException {
    Files.writeString(workspace.resolve("pom.xml"), "<project></project>\n");

    String dockerfile = PreviewSpringBootDockerfiles.cloudRun(workspace);

    assertThat(dockerfile)
        .contains("ENV PORT=8080")
        .contains("USER app")
        .contains("SERVER_PORT=${PORT:-8080}")
        .contains("-Dmaven.repo.local=/app/.m2 spring-boot:run");
  }

  @Test
  void cloudRunGradleUsesWrapperWhenPresent(@TempDir Path workspace) throws IOException {
    Files.writeString(workspace.resolve("build.gradle"), "plugins { id 'java' }\n");

    String dockerfile = PreviewSpringBootDockerfiles.cloudRun(workspace);

    assertThat(dockerfile)
        .contains("RUN chmod +x gradlew")
        .contains("SERVER_PORT=${PORT:-8080}")
        .contains("if [ -f gradlew ]")
        .doesNotContain("mvn compile");
  }
}
