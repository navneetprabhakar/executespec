package com.navneet.executespec.deployment.preview;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DevDockerfileGenerator")
class DevDockerfileGeneratorTest {

    DevDockerfileGenerator gen;

    @BeforeEach
    void setUp() {
        gen = new DevDockerfileGenerator();
    }

    // ── Detection tests ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Project type detection")
    class Detection {

        @Test
        @DisplayName("Detects Vite/React frontend from package.json without backend deps")
        void detectsNodeFrontend(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"my-app","dependencies":{"react":"^18","vite":"^5"}}
                    """);
            assertThat(gen.detect(ws)).isEqualTo(PreviewProjectType.NODE_FRONTEND);
        }

        @Test
        @DisplayName("Detects Express backend from package.json with express dependency")
        void detectsNodeBackendExpress(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"api","dependencies":{"express":"^4.18"}}
                    """);
            assertThat(gen.detect(ws)).isEqualTo(PreviewProjectType.NODE_BACKEND);
        }

        @Test
        @DisplayName("Detects Fastify backend from package.json")
        void detectsNodeBackendFastify(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"api","dependencies":{"fastify":"^4"}}
                    """);
            assertThat(gen.detect(ws)).isEqualTo(PreviewProjectType.NODE_BACKEND);
        }

        @Test
        @DisplayName("Detects NestJS backend from package.json")
        void detectsNodeBackendNestJs(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"api","dependencies":{"@nestjs/core":"^10"}}
                    """);
            assertThat(gen.detect(ws)).isEqualTo(PreviewProjectType.NODE_BACKEND);
        }

        @Test
        @DisplayName("Detects Hono backend from package.json")
        void detectsNodeBackendHono(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"api","dependencies":{"hono":"^4"}}
                    """);
            assertThat(gen.detect(ws)).isEqualTo(PreviewProjectType.NODE_BACKEND);
        }

        @Test
        @DisplayName("Detects Koa backend from package.json")
        void detectsNodeBackendKoa(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"api","dependencies":{"koa":"^2"}}
                    """);
            assertThat(gen.detect(ws)).isEqualTo(PreviewProjectType.NODE_BACKEND);
        }

        @Test
        @DisplayName("Detects Spring Boot from pom.xml with spring-boot-starter")
        void detectsSpringBoot(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("pom.xml"), """
                    <project>
                      <dependencies>
                        <dependency>
                          <groupId>org.springframework.boot</groupId>
                          <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                      </dependencies>
                    </project>
                    """);
            assertThat(gen.detect(ws)).isEqualTo(PreviewProjectType.SPRING_BOOT);
        }

        @Test
        @DisplayName("Detects Python from requirements.txt")
        void detectsPythonRequirements(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("requirements.txt"), "flask==3.0\n");
            assertThat(gen.detect(ws)).isEqualTo(PreviewProjectType.PYTHON);
        }

        @Test
        @DisplayName("Detects Python from pyproject.toml")
        void detectsPythonPyproject(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("pyproject.toml"), "[project]\nname = \"myapp\"\n");
            assertThat(gen.detect(ws)).isEqualTo(PreviewProjectType.PYTHON);
        }

        @Test
        @DisplayName("Returns null for empty workspace (unsupported)")
        void returnsNullForEmpty(@TempDir Path ws) {
            assertThat(gen.detect(ws)).isNull();
        }

        @Test
        @DisplayName("Returns null for pom.xml without spring-boot-starter (plain Java)")
        void returnsNullForPlainJava(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("pom.xml"), """
                    <project><dependencies></dependencies></project>
                    """);
            assertThat(gen.detect(ws)).isNull();
        }

        @Test
        @DisplayName("Spring Boot takes priority when both pom.xml and package.json exist")
        void springBootPriorityOverNode(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("pom.xml"), """
                    <project><dependencies>
                      <dependency><artifactId>spring-boot-starter-web</artifactId></dependency>
                    </dependencies></project>
                    """);
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"frontend","dependencies":{"react":"^18"}}
                    """);
            assertThat(gen.detect(ws)).isEqualTo(PreviewProjectType.SPRING_BOOT);
        }

        @Test
        @DisplayName("package.json with no dependencies is detected as NODE_FRONTEND")
        void packageJsonNoDeps(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"my-app"}
                    """);
            assertThat(gen.detect(ws)).isEqualTo(PreviewProjectType.NODE_FRONTEND);
        }

        @Test
        @DisplayName("Malformed package.json is treated as NODE_FRONTEND (file exists)")
        void malformedPackageJson(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), "NOT VALID JSON {{{");
            // detect should not throw, should still detect NODE_FRONTEND due to file presence
            assertThat(gen.detect(ws)).isEqualTo(PreviewProjectType.NODE_FRONTEND);
        }
    }

    // ── Dockerfile generation tests ─────────────────────────────────────────

    @Nested
    @DisplayName("Dockerfile generation")
    class Generation {

        @Test
        @DisplayName("NODE_FRONTEND with vite.config.ts uses npx vite command")
        void nodeFrontendVite(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), "{\"name\":\"app\"}");
            Files.writeString(ws.resolve("vite.config.ts"), "export default {}");
            String dockerfile = gen.generate(PreviewProjectType.NODE_FRONTEND, ws);

            assertThat(dockerfile).contains("FROM node:20-alpine");
            assertThat(dockerfile).contains("COPY package*.json ./");
            assertThat(dockerfile).contains("RUN npm install");
            assertThat(dockerfile).contains("EXPOSE 5173");
            assertThat(dockerfile).contains("npx", "vite", "--host", "0.0.0.0");
        }

        @Test
        @DisplayName("NODE_FRONTEND with dev script uses npm run dev")
        void nodeFrontendDevScript(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"app","scripts":{"dev":"next dev"}}
                    """);
            String dockerfile = gen.generate(PreviewProjectType.NODE_FRONTEND, ws);

            assertThat(dockerfile).contains("npm", "run", "dev");
        }

        @Test
        @DisplayName("NODE_FRONTEND fallback uses serve for static files")
        void nodeFrontendFallbackServe(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"app","scripts":{"build":"webpack build"}}
                    """);
            String dockerfile = gen.generate(PreviewProjectType.NODE_FRONTEND, ws);

            assertThat(dockerfile).contains("serve");
        }

        @Test
        @DisplayName("NODE_BACKEND with start script uses npm start")
        void nodeBackendStart(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"api","dependencies":{"express":"^4"},"scripts":{"start":"node server.js"}}
                    """);
            String dockerfile = gen.generate(PreviewProjectType.NODE_BACKEND, ws);

            assertThat(dockerfile).contains("FROM node:20-alpine");
            assertThat(dockerfile).contains("EXPOSE 3000");
            assertThat(dockerfile).contains("npm", "start");
            assertThat(dockerfile).contains("ENV HOST=0.0.0.0");
        }

        @Test
        @DisplayName("NODE_BACKEND with main field uses node <main>")
        void nodeBackendMainField(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"api","dependencies":{"express":"^4"},"main":"src/index.js"}
                    """);
            String dockerfile = gen.generate(PreviewProjectType.NODE_BACKEND, ws);

            assertThat(dockerfile).contains("node", "src/index.js");
        }

        @Test
        @DisplayName("NODE_BACKEND falls back to server.js when available")
        void nodeBackendServerJs(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"api","dependencies":{"express":"^4"}}
                    """);
            Files.writeString(ws.resolve("server.js"), "const express = require('express');");
            String dockerfile = gen.generate(PreviewProjectType.NODE_BACKEND, ws);

            assertThat(dockerfile).contains("node", "server.js");
        }

        @Test
        @DisplayName("NODE_BACKEND falls back to app.js when available")
        void nodeBackendAppJs(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"api","dependencies":{"express":"^4"}}
                    """);
            Files.writeString(ws.resolve("app.js"), "const express = require('express');");
            String dockerfile = gen.generate(PreviewProjectType.NODE_BACKEND, ws);

            assertThat(dockerfile).contains("node", "app.js");
        }

        @Test
        @DisplayName("NODE_BACKEND ultimate fallback is node index.js")
        void nodeBackendFallback(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"api","dependencies":{"express":"^4"}}
                    """);
            String dockerfile = gen.generate(PreviewProjectType.NODE_BACKEND, ws);

            assertThat(dockerfile).contains("node", "index.js");
        }

        @Test
        @DisplayName("SPRING_BOOT generates Maven-based Dockerfile with JDK 21")
        void springBoot(@TempDir Path ws) {
            String dockerfile = gen.generate(PreviewProjectType.SPRING_BOOT, ws);

            assertThat(dockerfile).contains("FROM eclipse-temurin:21");
            assertThat(dockerfile).contains("mvn dependency:go-offline");
            assertThat(dockerfile).contains("EXPOSE 8080");
            assertThat(dockerfile).contains("spring-boot:run");
            assertThat(dockerfile).contains("-DskipTests");
        }

        @Test
        @DisplayName("PYTHON with Django manage.py uses runserver")
        void pythonDjango(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("requirements.txt"), "django==5.0\n");
            Files.writeString(ws.resolve("manage.py"), "#!/usr/bin/env python\nimport django\n");
            String dockerfile = gen.generate(PreviewProjectType.PYTHON, ws);

            assertThat(dockerfile).contains("FROM python:3.12-slim");
            assertThat(dockerfile).contains("pip install --no-cache-dir -r requirements.txt");
            assertThat(dockerfile).contains("manage.py", "runserver", "0.0.0.0:8000");
            assertThat(dockerfile).contains("EXPOSE 8000");
        }

        @Test
        @DisplayName("PYTHON with FastAPI uses uvicorn")
        void pythonFastApi(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("requirements.txt"), "fastapi==0.110\nuvicorn\n");
            Files.writeString(ws.resolve("main.py"), "from fastapi import FastAPI\napp = FastAPI()\n");
            String dockerfile = gen.generate(PreviewProjectType.PYTHON, ws);

            assertThat(dockerfile).contains("uvicorn", "main:app", "--host", "0.0.0.0");
        }

        @Test
        @DisplayName("PYTHON with FastAPI auto-adds uvicorn to pip install if not in requirements")
        void pythonFastApiAutoUvicorn(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("requirements.txt"), "fastapi==0.110\n");
            Files.writeString(ws.resolve("app.py"), "from fastapi import FastAPI\napp = FastAPI()\n");
            String dockerfile = gen.generate(PreviewProjectType.PYTHON, ws);

            assertThat(dockerfile).contains("pip install --no-cache-dir -r requirements.txt uvicorn");
        }

        @Test
        @DisplayName("PYTHON with Flask sets FLASK_APP env var")
        void pythonFlask(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("requirements.txt"), "flask==3.0\n");
            Files.writeString(ws.resolve("app.py"), "from flask import Flask\napp = Flask(__name__)\n");
            String dockerfile = gen.generate(PreviewProjectType.PYTHON, ws);

            assertThat(dockerfile).contains("ENV FLASK_APP=app.py");
            assertThat(dockerfile).contains("flask", "run", "--host", "0.0.0.0");
        }

        @Test
        @DisplayName("PYTHON with pyproject.toml uses pip install .")
        void pythonPyproject(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("pyproject.toml"), "[project]\nname = \"myapp\"\n");
            Files.writeString(ws.resolve("main.py"), "print('hello')\n");
            String dockerfile = gen.generate(PreviewProjectType.PYTHON, ws);

            assertThat(dockerfile).contains("pip install --no-cache-dir .");
        }

        @Test
        @DisplayName("PYTHON fallback uses python main.py")
        void pythonFallbackMain(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("requirements.txt"), "something==1.0\n");
            Files.writeString(ws.resolve("main.py"), "print('hello')\n");
            String dockerfile = gen.generate(PreviewProjectType.PYTHON, ws);

            assertThat(dockerfile).contains("python", "main.py");
        }

        @Test
        @DisplayName("All generated Dockerfiles contain LABEL preview=true")
        void allHavePreviewLabel(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), "{\"name\":\"app\"}");
            for (PreviewProjectType type : PreviewProjectType.values()) {
                String dockerfile = gen.generate(type, ws);
                assertThat(dockerfile).as("Dockerfile for " + type).contains("LABEL preview=true");
            }
        }
    }

    // ── .dockerignore tests ─────────────────────────────────────────────────

    @Nested
    @DisplayName("dockerIgnore generation")
    class DockerIgnore {

        @Test
        @DisplayName("Contains common build artifacts and IDE folders")
        void containsExpectedEntries() {
            String content = gen.dockerIgnore();
            assertThat(content).contains(".git");
            assertThat(content).contains("node_modules");
            assertThat(content).contains("target");
            assertThat(content).contains(".idea");
            assertThat(content).contains("Dockerfile");
        }
    }
}
