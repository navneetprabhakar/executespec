package com.navneet.executespec.testgen;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates a minimal Maven test project in workspace-test/{runId}/
 * with RestAssured + JUnit 5 + AssertJ dependencies.
 * This project is independent of the generated application code.
 */
@Component
@Slf4j
public class TestProjectScaffold {

    private static final String POM_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.executespec</groupId>
                <artifactId>executespec-tests-%s</artifactId>
                <version>1.0.0</version>

                <properties>
                    <maven.compiler.source>21</maven.compiler.source>
                    <maven.compiler.target>21</maven.compiler.target>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    <junit.version>5.10.2</junit.version>
                    <restassured.version>5.4.0</restassured.version>
                    <assertj.version>3.25.3</assertj.version>
                </properties>

                <dependencies>
                    <dependency>
                        <groupId>io.rest-assured</groupId>
                        <artifactId>rest-assured</artifactId>
                        <version>${restassured.version}</version>
                    </dependency>
                    <dependency>
                        <groupId>org.junit.jupiter</groupId>
                        <artifactId>junit-jupiter</artifactId>
                        <version>${junit.version}</version>
                    </dependency>
                    <dependency>
                        <groupId>org.assertj</groupId>
                        <artifactId>assertj-core</artifactId>
                        <version>${assertj.version}</version>
                    </dependency>
                    <dependency>
                        <groupId>com.fasterxml.jackson.core</groupId>
                        <artifactId>jackson-databind</artifactId>
                        <version>2.17.0</version>
                    </dependency>
                </dependencies>

                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-surefire-plugin</artifactId>
                            <version>3.2.5</version>
                            <configuration>
                                <systemPropertyVariables>
                                    <app.base-url>${app.base-url}</app.base-url>
                                </systemPropertyVariables>
                                <reportsDirectory>${project.build.directory}/surefire-reports</reportsDirectory>
                            </configuration>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """;

    /**
     * Create the test project directory structure and pom.xml.
     *
     * @param runId the run ID
     * @return the root path of the test project
     */
    public Path scaffold(String runId) throws IOException {
        Path root = Path.of("workspace-test", runId);
        Path testSrcDir = root.resolve("src/test/java/com/executespec/tests");
        Files.createDirectories(testSrcDir);

        Path pomFile = root.resolve("pom.xml");
        if (!Files.exists(pomFile)) {
            Files.writeString(pomFile, POM_TEMPLATE.formatted(runId));
        }

        log.info("Scaffolded test project at {} for run {}", root, runId);
        return root;
    }

    /**
     * Get the test source directory for writing test files.
     */
    public Path getTestSourceDir(String runId) {
        return Path.of("workspace-test", runId, "src", "test", "java", "com", "executespec", "tests");
    }
}
