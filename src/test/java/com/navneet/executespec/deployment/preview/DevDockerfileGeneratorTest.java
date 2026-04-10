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
        @DisplayName("NODE_FRONTEND with dev script uses vite or npm run dev")
        void nodeFrontendDevScript(@TempDir Path ws) throws IOException {
            // Package with dev script but no explicit vite dependency →
            // generator auto-installs vite and uses npx vite
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"app","scripts":{"dev":"next dev"}}
                    """);
            String dockerfile = gen.generate(PreviewProjectType.NODE_FRONTEND, ws);

            assertThat(dockerfile).contains("vite");
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

    // ── Monorepo detection tests ──────────────────────────────────────────

    @Nested
    @DisplayName("Monorepo detection")
    class MonorepoDetection {

        @Test
        @DisplayName("Detects monorepo with frontend+backend workspaces as NODE_FRONTEND")
        void detectsFullMonorepo(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"monorepo","workspaces":["frontend","backend"]}
                    """);
            Files.createDirectories(ws.resolve("frontend"));
            Files.writeString(ws.resolve("frontend/package.json"), """
                    {"name":"frontend","dependencies":{"react":"^18"},"devDependencies":{"vite":"^5"}}
                    """);
            Files.createDirectories(ws.resolve("backend"));
            Files.writeString(ws.resolve("backend/package.json"), """
                    {"name":"backend","dependencies":{"express":"^4"}}
                    """);

            assertThat(gen.detect(ws)).isEqualTo(PreviewProjectType.NODE_FRONTEND);
        }

        @Test
        @DisplayName("Detects monorepo with only backend workspace as NODE_BACKEND")
        void detectsBackendOnlyMonorepo(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"monorepo","workspaces":["server"]}
                    """);
            Files.createDirectories(ws.resolve("server"));
            Files.writeString(ws.resolve("server/package.json"), """
                    {"name":"server","dependencies":{"fastify":"^4"}}
                    """);

            assertThat(gen.detect(ws)).isEqualTo(PreviewProjectType.NODE_BACKEND);
        }

        @Test
        @DisplayName("Detects monorepo with only frontend workspace as NODE_FRONTEND")
        void detectsFrontendOnlyMonorepo(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"monorepo","workspaces":["web"]}
                    """);
            Files.createDirectories(ws.resolve("web"));
            Files.writeString(ws.resolve("web/package.json"), """
                    {"name":"web","dependencies":{"react":"^18"}}
                    """);

            assertThat(gen.detect(ws)).isEqualTo(PreviewProjectType.NODE_FRONTEND);
        }

        @Test
        @DisplayName("Workspaces array with no existing sub-dirs falls back to standard detection")
        void workspacesWithMissingDirs(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"monorepo","workspaces":["nonexistent"]}
                    """);
            // nonexistent/ dir does not exist → not a monorepo → falls back to NODE_FRONTEND
            assertThat(gen.detect(ws)).isEqualTo(PreviewProjectType.NODE_FRONTEND);
        }

        @Test
        @DisplayName("Empty workspaces array is not treated as monorepo")
        void emptyWorkspacesArray(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"app","workspaces":[],"dependencies":{"express":"^4"}}
                    """);
            assertThat(gen.detect(ws)).isEqualTo(PreviewProjectType.NODE_BACKEND);
        }

        @Test
        @DisplayName("Vue monorepo detected as NODE_FRONTEND")
        void vueMonorepo(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"monorepo","workspaces":["client","api"]}
                    """);
            Files.createDirectories(ws.resolve("client"));
            Files.writeString(ws.resolve("client/package.json"), """
                    {"name":"client","dependencies":{"vue":"^3"}}
                    """);
            Files.createDirectories(ws.resolve("api"));
            Files.writeString(ws.resolve("api/package.json"), """
                    {"name":"api","dependencies":{"koa":"^2"}}
                    """);

            assertThat(gen.detect(ws)).isEqualTo(PreviewProjectType.NODE_FRONTEND);
        }
    }

    // ── Monorepo Dockerfile generation tests ────────────────────────────────

    @Nested
    @DisplayName("Monorepo Dockerfile generation")
    class MonorepoGeneration {

        @Test
        @DisplayName("Full-stack monorepo exposes both ports and runs both services")
        void fullStackMonorepo(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"monorepo","workspaces":["frontend","backend"]}
                    """);
            Files.createDirectories(ws.resolve("frontend"));
            Files.writeString(ws.resolve("frontend/package.json"), """
                    {"name":"frontend","dependencies":{"react":"^18"},"devDependencies":{"vite":"^5"}}
                    """);
            Files.createDirectories(ws.resolve("backend"));
            Files.writeString(ws.resolve("backend/package.json"), """
                    {"name":"backend","dependencies":{"express":"^4"}}
                    """);
            Files.writeString(ws.resolve("backend/server.js"),
                    "const PORT = process.env.PORT || 3001;");

            String df = gen.generate(PreviewProjectType.NODE_FRONTEND, ws);

            assertThat(df).contains("FROM node:20-alpine");
            assertThat(df).contains("LABEL preview=true");
            assertThat(df).contains("COPY package.json package-lock.json* ./");
            assertThat(df).contains("COPY frontend/package.json ./frontend/");
            assertThat(df).contains("COPY backend/package.json ./backend/");
            assertThat(df).contains("RUN npm install --legacy-peer-deps");
            assertThat(df).contains("COPY . .");
            assertThat(df).contains("EXPOSE 5173");   // frontend port
            assertThat(df).contains("vite --host 0.0.0.0");
        }

        @Test
        @DisplayName("Backend-only monorepo exposes backend port and uses dev script")
        void backendOnlyMonorepo(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"monorepo","workspaces":["api"]}
                    """);
            Files.createDirectories(ws.resolve("api"));
            Files.writeString(ws.resolve("api/package.json"), """
                    {"name":"api","dependencies":{"express":"^4"}}
                    """);

            String df = gen.generate(PreviewProjectType.NODE_BACKEND, ws);

            assertThat(df).contains("FROM node:20-alpine");
            assertThat(df).contains("COPY api/package.json ./api/");
            assertThat(df).contains("npm install --legacy-peer-deps");
            assertThat(df).contains("EXPOSE 3000");
            assertThat(df).contains("--prefix", "api");
        }

        @Test
        @DisplayName("Frontend-only monorepo starts vite with prefix")
        void frontendOnlyMonorepo(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"monorepo","workspaces":["web"]}
                    """);
            Files.createDirectories(ws.resolve("web"));
            Files.writeString(ws.resolve("web/package.json"), """
                    {"name":"web","dependencies":{"react":"^18"},"devDependencies":{"@vitejs/plugin-react":"^4"}}
                    """);

            String df = gen.generate(PreviewProjectType.NODE_FRONTEND, ws);

            assertThat(df).contains("EXPOSE 5173");
            assertThat(df).contains("--prefix", "web");
            assertThat(df).contains("vite");
        }

        @Test
        @DisplayName("Monorepo with no recognized sub-packages falls back to npm run dev")
        void monorepoFallback(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"monorepo","workspaces":["lib"]}
                    """);
            Files.createDirectories(ws.resolve("lib"));
            Files.writeString(ws.resolve("lib/package.json"), """
                    {"name":"lib","dependencies":{"lodash":"^4"}}
                    """);

            String df = gen.generate(PreviewProjectType.NODE_FRONTEND, ws);

            assertThat(df).contains("npm", "run", "dev");
        }
    }

    // ── Backend port detection tests ────────────────────────────────────────

    @Nested
    @DisplayName("Backend port detection in monorepo")
    class BackendPortDetection {

        @Test
        @DisplayName("Detects PORT || 3001 pattern from server.js")
        void detectsPortOrPattern(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"monorepo","workspaces":["backend"]}
                    """);
            Files.createDirectories(ws.resolve("backend"));
            Files.writeString(ws.resolve("backend/package.json"), """
                    {"name":"backend","dependencies":{"express":"^4"}}
                    """);
            Files.writeString(ws.resolve("backend/server.js"),
                    "const PORT = process.env.PORT || 3001;\napp.listen(PORT);");

            String df = gen.generate(PreviewProjectType.NODE_BACKEND, ws);

            assertThat(df).contains("EXPOSE 3001");
        }

        @Test
        @DisplayName("Detects listen(4000) pattern from main file")
        void detectsListenPattern(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"monorepo","workspaces":["api"]}
                    """);
            Files.createDirectories(ws.resolve("api"));
            Files.writeString(ws.resolve("api/package.json"), """
                    {"name":"api","dependencies":{"express":"^4"},"main":"src/index.js"}
                    """);
            Files.createDirectories(ws.resolve("api/src"));
            Files.writeString(ws.resolve("api/src/index.js"),
                    "app.listen(4000, () => console.log('ready'));");

            String df = gen.generate(PreviewProjectType.NODE_BACKEND, ws);

            assertThat(df).contains("EXPOSE 4000");
        }

        @Test
        @DisplayName("Defaults to 3000 when no port pattern found")
        void defaultsTo3000(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"monorepo","workspaces":["svc"]}
                    """);
            Files.createDirectories(ws.resolve("svc"));
            Files.writeString(ws.resolve("svc/package.json"), """
                    {"name":"svc","dependencies":{"hono":"^4"}}
                    """);
            Files.writeString(ws.resolve("svc/index.js"),
                    "export default { fetch(req) { return new Response('ok'); } };");

            String df = gen.generate(PreviewProjectType.NODE_BACKEND, ws);

            assertThat(df).contains("EXPOSE 3000");
        }
    }

    // ── Node install safety tests ───────────────────────────────────────────

    @Nested
    @DisplayName("Node install safety")
    class NodeInstallSafety {

        @Test
        @DisplayName("NODE_FRONTEND Dockerfile uses --legacy-peer-deps")
        void frontendUsesLegacyPeerDeps(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), "{\"name\":\"app\"}");
            String df = gen.generate(PreviewProjectType.NODE_FRONTEND, ws);

            assertThat(df).contains("npm install --legacy-peer-deps");
        }

        @Test
        @DisplayName("NODE_BACKEND Dockerfile uses --legacy-peer-deps")
        void backendUsesLegacyPeerDeps(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"api","dependencies":{"express":"^4"}}
                    """);
            String df = gen.generate(PreviewProjectType.NODE_BACKEND, ws);

            assertThat(df).contains("npm install --legacy-peer-deps");
        }

        @Test
        @DisplayName("Monorepo Dockerfile uses --legacy-peer-deps")
        void monorepoUsesLegacyPeerDeps(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"monorepo","workspaces":["frontend"]}
                    """);
            Files.createDirectories(ws.resolve("frontend"));
            Files.writeString(ws.resolve("frontend/package.json"), """
                    {"name":"frontend","dependencies":{"react":"^18"}}
                    """);

            String df = gen.generate(PreviewProjectType.NODE_FRONTEND, ws);

            assertThat(df).contains("npm install --legacy-peer-deps");
        }
    }

    // ── Python edge case tests ──────────────────────────────────────────────

    @Nested
    @DisplayName("Python edge cases")
    class PythonEdgeCases {

        @Test
        @DisplayName("Python with app.py fallback (no main.py, no framework)")
        void pythonAppPyFallback(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("requirements.txt"), "requests==2.31\n");
            Files.writeString(ws.resolve("app.py"), "print('running app')\n");

            String df = gen.generate(PreviewProjectType.PYTHON, ws);
            assertThat(df).contains("python", "app.py");
        }

        @Test
        @DisplayName("Python with no requirements or pyproject falls back gracefully")
        void pythonNoRequirements(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("main.py"), "print('hello')\n");

            String df = gen.generate(PreviewProjectType.PYTHON, ws);

            assertThat(df).contains("# No requirements file found");
            assertThat(df).contains("python", "main.py");
        }

        @Test
        @DisplayName("Python uses COPY with glob for requirements and pyproject")
        void pythonCopiesDepFiles(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("requirements.txt"), "flask==3.0\n");
            Files.writeString(ws.resolve("app.py"), "from flask import Flask\n");

            String df = gen.generate(PreviewProjectType.PYTHON, ws);

            assertThat(df).contains("COPY requirements.txt* pyproject.toml* ./");
        }
    }

    // ── Full-stack detection tests ──────────────────────────────────────────

    @Nested
    @DisplayName("Full-stack detection (directory-based)")
    class FullStackDetection {

        @Test
        @DisplayName("Detects React frontend/ + Express backend/ as FULL_STACK")
        void detectsNodeNodeFullStack(@TempDir Path ws) throws IOException {
            Files.createDirectories(ws.resolve("frontend"));
            Files.writeString(ws.resolve("frontend/package.json"), """
                    {"name":"frontend","dependencies":{"react":"^18"},"devDependencies":{"vite":"^5"}}
                    """);
            Files.createDirectories(ws.resolve("backend"));
            Files.writeString(ws.resolve("backend/package.json"), """
                    {"name":"backend","dependencies":{"express":"^4"}}
                    """);

            assertThat(gen.detect(ws)).isEqualTo(PreviewProjectType.FULL_STACK);
        }

        @Test
        @DisplayName("Detects React client/ + Spring Boot server/ as FULL_STACK")
        void detectsNodeSpringBootFullStack(@TempDir Path ws) throws IOException {
            Files.createDirectories(ws.resolve("client"));
            Files.writeString(ws.resolve("client/package.json"), """
                    {"name":"client","dependencies":{"react":"^18"}}
                    """);
            Files.createDirectories(ws.resolve("server"));
            Files.writeString(ws.resolve("server/pom.xml"), """
                    <project><dependencies>
                      <dependency><artifactId>spring-boot-starter-web</artifactId></dependency>
                    </dependencies></project>
                    """);

            assertThat(gen.detect(ws)).isEqualTo(PreviewProjectType.FULL_STACK);
        }

        @Test
        @DisplayName("Detects React frontend/ + Python api/ as FULL_STACK")
        void detectsNodePythonFullStack(@TempDir Path ws) throws IOException {
            Files.createDirectories(ws.resolve("frontend"));
            Files.writeString(ws.resolve("frontend/package.json"), """
                    {"name":"frontend","dependencies":{"vue":"^3"}}
                    """);
            Files.createDirectories(ws.resolve("api"));
            Files.writeString(ws.resolve("api/requirements.txt"), "fastapi==0.110\n");

            assertThat(gen.detect(ws)).isEqualTo(PreviewProjectType.FULL_STACK);
        }

        @Test
        @DisplayName("Only backend/ (no frontend/) detected as backend type, not FULL_STACK")
        void backendOnlyIsNotFullStack(@TempDir Path ws) throws IOException {
            Files.createDirectories(ws.resolve("backend"));
            Files.writeString(ws.resolve("backend/package.json"), """
                    {"name":"backend","dependencies":{"express":"^4"}}
                    """);

            assertThat(gen.detect(ws)).isEqualTo(PreviewProjectType.NODE_BACKEND);
        }

        @Test
        @DisplayName("Only frontend/ (no backend/) detected as NODE_FRONTEND")
        void frontendOnlyIsNodeFrontend(@TempDir Path ws) throws IOException {
            Files.createDirectories(ws.resolve("frontend"));
            Files.writeString(ws.resolve("frontend/package.json"), """
                    {"name":"frontend","dependencies":{"react":"^18"}}
                    """);

            assertThat(gen.detect(ws)).isEqualTo(PreviewProjectType.NODE_FRONTEND);
        }

        @Test
        @DisplayName("Root package.json (frontend) + backend/ subdir detects as FULL_STACK")
        void rootLevelWithBackendSubdir(@TempDir Path ws) throws IOException {
            // Root has react (frontend dep) + backend/ with express → FULL_STACK
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"app","dependencies":{"react":"^18"}}
                    """);
            Files.createDirectories(ws.resolve("backend"));
            Files.writeString(ws.resolve("backend/package.json"), """
                    {"name":"backend","dependencies":{"express":"^4"}}
                    """);

            assertThat(gen.detect(ws)).isEqualTo(PreviewProjectType.FULL_STACK);
        }

        @Test
        @DisplayName("Root-only frontend package.json without backend subdir is NODE_FRONTEND")
        void rootOnlyFrontendIsNodeFrontend(@TempDir Path ws) throws IOException {
            Files.writeString(ws.resolve("package.json"), """
                    {"name":"app","dependencies":{"react":"^18"}}
                    """);

            assertThat(gen.detect(ws)).isEqualTo(PreviewProjectType.NODE_FRONTEND);
        }
    }

    // ── Full-stack Dockerfile generation tests ───────────────────────────────

    @Nested
    @DisplayName("Full-stack Dockerfile generation")
    class FullStackGeneration {

        @Test
        @DisplayName("Node+Node full-stack runs both services with sh -c")
        void nodeNodeFullStack(@TempDir Path ws) throws IOException {
            Files.createDirectories(ws.resolve("frontend"));
            Files.writeString(ws.resolve("frontend/package.json"), """
                    {"name":"frontend","dependencies":{"react":"^18"}}
                    """);
            Files.createDirectories(ws.resolve("backend"));
            Files.writeString(ws.resolve("backend/package.json"), """
                    {"name":"backend","dependencies":{"express":"^4"},"scripts":{"start":"node server.js"}}
                    """);

            String df = gen.generate(PreviewProjectType.FULL_STACK, ws);

            assertThat(df).contains("FROM node:20-alpine");
            assertThat(df).contains("LABEL preview=true");
            assertThat(df).contains("COPY frontend/package*.json ./frontend/");
            assertThat(df).contains("COPY backend/package*.json ./backend/");
            assertThat(df).contains("npm install --legacy-peer-deps");
            assertThat(df).contains("EXPOSE 5173");
            assertThat(df).contains("npm start");
            assertThat(df).contains("vite --host 0.0.0.0");
        }

        @Test
        @DisplayName("Node+SpringBoot full-stack uses JDK image with Node installed")
        void nodeSpringBootFullStack(@TempDir Path ws) throws IOException {
            Files.createDirectories(ws.resolve("client"));
            Files.writeString(ws.resolve("client/package.json"), """
                    {"name":"client","dependencies":{"react":"^18"}}
                    """);
            Files.createDirectories(ws.resolve("server"));
            Files.writeString(ws.resolve("server/pom.xml"), """
                    <project><dependencies>
                      <dependency><artifactId>spring-boot-starter-web</artifactId></dependency>
                    </dependencies></project>
                    """);

            String df = gen.generate(PreviewProjectType.FULL_STACK, ws);

            assertThat(df).contains("FROM eclipse-temurin:21");
            assertThat(df).contains("nodejs"); // npm bundled via nodesource
            assertThat(df).contains("COPY server/pom.xml ./server/");
            assertThat(df).contains("mvn dependency:go-offline");
            assertThat(df).contains("COPY client/package*.json ./client/");
            assertThat(df).contains("EXPOSE 5173 8080");
            assertThat(df).contains("spring-boot:run");
            assertThat(df).contains("vite --host 0.0.0.0");
        }

        @Test
        @DisplayName("Node+Python full-stack uses Python image with Node installed")
        void nodePythonFullStack(@TempDir Path ws) throws IOException {
            Files.createDirectories(ws.resolve("frontend"));
            Files.writeString(ws.resolve("frontend/package.json"), """
                    {"name":"frontend","dependencies":{"react":"^18"}}
                    """);
            Files.createDirectories(ws.resolve("api"));
            Files.writeString(ws.resolve("api/requirements.txt"), "fastapi==0.110\n");
            Files.writeString(ws.resolve("api/main.py"), "from fastapi import FastAPI\napp = FastAPI()\n");

            String df = gen.generate(PreviewProjectType.FULL_STACK, ws);

            assertThat(df).contains("FROM python:3.12-slim");
            assertThat(df).contains("nodejs"); // npm bundled via nodesource
            assertThat(df).contains("COPY api/requirements.txt");
            assertThat(df).contains("pip install");
            assertThat(df).contains("COPY frontend/package*.json ./frontend/");
            assertThat(df).contains("EXPOSE 5173 8000");
            assertThat(df).contains("uvicorn");
            assertThat(df).contains("vite --host 0.0.0.0");
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
