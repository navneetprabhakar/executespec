package com.navneet.agentteam.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FileSystemTool credential isolation")
class FileSystemToolTest {

    @TempDir
    Path tempDir;

    private FileSystemTool fsTool;

    @BeforeEach
    void setUp() throws IOException {
        fsTool = new FileSystemTool();
        ReflectionTestUtils.setField(fsTool, "workspaceRoot", tempDir.toString());

        // Create a run workspace directory
        Files.createDirectories(tempDir.resolve("test-run"));
    }

    @Nested
    @DisplayName("Blocked credential files")
    class BlockedFiles {

        @Test
        @DisplayName("application.yml is blocked")
        void applicationYml() throws IOException {
            Files.writeString(tempDir.resolve("test-run/application.yml"), "secret: password");
            String result = fsTool.readFile("test-run", "application.yml");
            assertThat(result).contains("ACCESS DENIED");
        }

        @Test
        @DisplayName(".env is blocked")
        void dotEnv() throws IOException {
            Files.writeString(tempDir.resolve("test-run/.env"), "DB_PASS=secret");
            String result = fsTool.readFile("test-run", ".env");
            assertThat(result).contains("ACCESS DENIED");
        }

        @Test
        @DisplayName("secrets.yml is blocked")
        void secretsYml() throws IOException {
            Files.writeString(tempDir.resolve("test-run/secrets.yml"), "api-key: 12345");
            String result = fsTool.readFile("test-run", "secrets.yml");
            assertThat(result).contains("ACCESS DENIED");
        }

        @Test
        @DisplayName("credentials.json is blocked")
        void credentialsJson() throws IOException {
            Files.writeString(tempDir.resolve("test-run/credentials.json"), "{}");
            String result = fsTool.readFile("test-run", "credentials.json");
            assertThat(result).contains("ACCESS DENIED");
        }
    }

    @Nested
    @DisplayName("Allowed files")
    class AllowedFiles {

        @Test
        @DisplayName("Java source file is readable")
        void javaSourceFile() throws IOException {
            Files.createDirectories(tempDir.resolve("test-run/src/main/java"));
            Path javaFile = tempDir.resolve("test-run/src/main/java/Foo.java");
            Files.writeString(javaFile, "public class Foo {}");
            String result = fsTool.readFile("test-run", "src/main/java/Foo.java");
            assertThat(result).doesNotStartWith("ERROR");
            assertThat(result).contains("public class Foo");
        }

        @Test
        @DisplayName("README.md is readable")
        void readmeMd() throws IOException {
            Files.writeString(tempDir.resolve("test-run/README.md"), "# My Project");
            String result = fsTool.readFile("test-run", "README.md");
            assertThat(result).doesNotStartWith("ERROR");
            assertThat(result).contains("# My Project");
        }

        @Test
        @DisplayName("pom.xml is readable")
        void pomXml() throws IOException {
            Files.writeString(tempDir.resolve("test-run/pom.xml"), "<project/>");
            String result = fsTool.readFile("test-run", "pom.xml");
            assertThat(result).doesNotStartWith("ERROR");
        }
    }

    @Nested
    @DisplayName("Path traversal protection")
    class PathTraversal {

        @Test
        @DisplayName("path traversal escape is blocked")
        void pathTraversalEscape() {
            // Attempt to escape the workspace via path traversal
            String result = fsTool.readFile("test-run", "../../application.yml");
            // Should be blocked — either SecurityException caught (returns error) or ACCESS
            // DENIED
            assertThat(result).startsWith("ACCESS DENIED");
        }
    }
}
