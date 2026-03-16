package com.navneet.executespec.deployment.preview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Component
@Slf4j
public class DevDockerfileGenerator {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final long MAX_FILE_SIZE = 50 * 1024; // 50 KB
    private static final int MAX_PYTHON_FILES = 20;

    private static final Set<String> NODE_BACKEND_DEPS = Set.of(
            "express", "fastify", "koa", "hono", "@nestjs/core"
    );

    private static final Set<String> FRONTEND_DIR_NAMES = Set.of(
            "frontend", "client", "web", "app", "ui"
    );
    private static final Set<String> BACKEND_DIR_NAMES = Set.of(
            "backend", "server", "api", "service"
    );

    /**
     * Captures the layout of a full-stack project with separate frontend/backend subdirectories.
     */
    record FullStackLayout(String frontendDir, String backendDir,
                           PreviewProjectType frontendType, PreviewProjectType backendType) {
        boolean hasFrontend() { return frontendDir != null; }
        boolean hasBackend()  { return backendDir != null; }
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Detect project type from workspace contents.
     * Returns null if unsupported.
     */
    public PreviewProjectType detect(Path workspace) {
        // 1. Spring Boot (check before package.json in case both exist)
        Path pomXml = workspace.resolve("pom.xml");
        if (Files.exists(pomXml) && fileContainsPattern(pomXml, "spring-boot-starter")) {
            return PreviewProjectType.SPRING_BOOT;
        }

        // 2. Node (frontend vs backend)
        Path packageJson = workspace.resolve("package.json");
        if (Files.exists(packageJson)) {
            JsonNode pkg = parsePackageJson(workspace);
            if (pkg != null) {
                // Monorepo detection: if root has "workspaces", check sub-packages
                if (isMonorepo(pkg, workspace)) {
                    return detectMonorepoType(pkg, workspace);
                }

                JsonNode deps = pkg.get("dependencies");
                if (deps != null) {
                    for (String backendDep : NODE_BACKEND_DEPS) {
                        if (deps.has(backendDep)) {
                            return PreviewProjectType.NODE_BACKEND;
                        }
                    }
                }
            }
            return PreviewProjectType.NODE_FRONTEND;
        }

        // 3. Python
        if (Files.exists(workspace.resolve("requirements.txt")) ||
            Files.exists(workspace.resolve("pyproject.toml"))) {
            return PreviewProjectType.PYTHON;
        }

        // 4. Full-stack: scan subdirectories (frontend/ + backend/, client/ + server/, etc.)
        // This handles projects where frontend and backend are in separate directories
        // without npm workspaces or a root-level package.json
        FullStackLayout layout = detectFullStack(workspace);
        if (layout != null && (layout.hasFrontend() || layout.hasBackend())) {
            if (layout.hasFrontend() && layout.hasBackend()) {
                return PreviewProjectType.FULL_STACK;
            }
            // Single subdir detected — return its type
            return layout.hasFrontend() ? layout.frontendType() : layout.backendType();
        }

        // 5. Static HTML — fallback if any .html file exists in workspace root
        if (hasStaticHtml(workspace)) {
            return PreviewProjectType.STATIC_HTML;
        }

        return null;
    }

    /**
     * Generate Dockerfile content for the given project type.
     * Does NOT write to disk.
     */
    public String generate(PreviewProjectType type, Path workspace) {
        // Monorepo gets a specialized Dockerfile
        JsonNode pkg = parsePackageJson(workspace);
        if (pkg != null && isMonorepo(pkg, workspace)) {
            return monorepoDockerfile(pkg, workspace, type);
        }

        // Full-stack gets a multi-service Dockerfile
        if (type == PreviewProjectType.FULL_STACK) {
            FullStackLayout layout = detectFullStack(workspace);
            if (layout != null) {
                return fullStackDockerfile(workspace, layout);
            }
        }

        return switch (type) {
            case NODE_FRONTEND -> nodeFrontendDockerfile(workspace);
            case NODE_BACKEND -> nodeBackendDockerfile(workspace);
            case SPRING_BOOT -> springBootDockerfile();
            case PYTHON -> pythonDockerfile(workspace);
            case FULL_STACK -> nodeFrontendDockerfile(workspace); // fallback
            case STATIC_HTML -> staticHtmlDockerfile();
        };
    }

    /**
     * Generate a Cloud Run-compatible Dockerfile.
     * Ensures the container listens on $PORT (Cloud Run requirement).
     * Replaces hardcoded ports with $PORT and adds ENV PORT=8080 default.
     */
    public String generateForCloudRun(PreviewProjectType type, Path workspace) {
        String dockerfile = generate(type, workspace);

        // Add ENV PORT=8080 after FROM line if not already present
        if (!dockerfile.contains("ENV PORT")) {
            dockerfile = dockerfile.replaceFirst("(FROM [^\n]+\n)", "$1ENV PORT=8080\n");
        }

        // Replace hardcoded port references with $PORT for Cloud Run compatibility
        // Vite: --port 5173 → --port $PORT
        dockerfile = dockerfile.replace("--port\", \"5173\"", "--port\", \"${PORT:-8080}\"");
        dockerfile = dockerfile.replace("--port 5173", "--port ${PORT:-8080}");
        // serve: -l 5173 → -l $PORT
        dockerfile = dockerfile.replace("-l\", \"5173\"", "-l\", \"${PORT:-8080}\"");
        // Node backend: 3000 → $PORT (EXPOSE line)
        dockerfile = dockerfile.replace("EXPOSE 5173", "EXPOSE 8080");
        dockerfile = dockerfile.replace("EXPOSE 3000", "EXPOSE 8080");
        // Python: --port 8000 → --port $PORT (both shell form and JSON exec form)
        dockerfile = dockerfile.replace("--port 8000", "--port ${PORT:-8080}");
        dockerfile = dockerfile.replace("\"--port\", \"8000\"", "\"--port\", \"8080\"");
        dockerfile = dockerfile.replace("0.0.0.0:8000", "0.0.0.0:8080");
        // Spring Boot already uses 8080 by default

        return dockerfile;
    }

    /**
     * Returns .dockerignore content.
     */
    public String dockerIgnore() {
        return """
                .git
                node_modules
                target
                build
                dist
                .idea
                *.log
                .dockerignore
                Dockerfile
                """;
    }

    // ── Private Dockerfile generators ───────────────────────────────────────

    private String nodeFrontendDockerfile(Path workspace) {
        String cmd;
        boolean hasViteConfig = Files.exists(workspace.resolve("vite.config.js"))
                || Files.exists(workspace.resolve("vite.config.ts"));

        if (hasViteConfig) {
            cmd = """
                    CMD ["npx", "vite", "--host", "0.0.0.0", "--port", "5173"]""";
        } else {
            JsonNode pkg = parsePackageJson(workspace);
            boolean hasDevScript = pkg != null && pkg.has("scripts")
                    && pkg.get("scripts").has("dev");
            if (hasDevScript) {
                cmd = """
                        CMD ["npm", "run", "dev"]""";
            } else {
                cmd = """
                        RUN npm install -g serve
                        CMD ["npx", "serve", "-s", ".", "-l", "5173"]""";
            }
        }

        return """
                FROM node:20-alpine
                WORKDIR /app
                LABEL preview=true
                COPY package*.json ./
                RUN npm install --ignore-scripts && npm rebuild
                COPY . .
                EXPOSE 5173
                """ + cmd.stripLeading() + "\n";
    }

    private String nodeBackendDockerfile(Path workspace) {
        String cmd;
        JsonNode pkg = parsePackageJson(workspace);

        if (pkg != null && pkg.has("scripts") && pkg.get("scripts").has("start")) {
            cmd = """
                    CMD ["npm", "start"]""";
        } else if (pkg != null && pkg.has("main")) {
            String main = pkg.get("main").asText("index.js");
            cmd = "CMD [\"node\", \"" + main + "\"]";
        } else if (Files.exists(workspace.resolve("server.js"))) {
            cmd = """
                    CMD ["node", "server.js"]""";
        } else if (Files.exists(workspace.resolve("app.js"))) {
            cmd = """
                    CMD ["node", "app.js"]""";
        } else {
            cmd = """
                    CMD ["node", "index.js"]""";
        }

        return """
                FROM node:20-alpine
                WORKDIR /app
                LABEL preview=true
                ENV HOST=0.0.0.0
                COPY package*.json ./
                RUN npm install --ignore-scripts && npm rebuild
                COPY . .
                EXPOSE 3000
                """ + cmd.stripLeading() + "\n";
    }

    private String springBootDockerfile() {
        return """
                FROM eclipse-temurin:21
                WORKDIR /app
                LABEL preview=true
                RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*
                COPY pom.xml .
                RUN mvn dependency:go-offline -q -B
                COPY src/ src/
                EXPOSE 8080
                CMD ["mvn", "spring-boot:run", "-DskipTests", "-q"]
                """;
    }

    private String pythonDockerfile(Path workspace) {
        // Resolve CMD
        String cmd;
        String envLine = "";

        if (Files.exists(workspace.resolve("manage.py"))) {
            // Django
            cmd = """
                    CMD ["python", "manage.py", "runserver", "0.0.0.0:8000"]""";
        } else {
            // Check for FastAPI
            String fastApiFile = findPythonFileWithPattern(workspace, "from fastapi", "import fastapi");
            if (fastApiFile != null) {
                String module = fastApiFile.replace(".py", "");
                cmd = "CMD [\"uvicorn\", \"" + module + ":app\", \"--host\", \"0.0.0.0\", \"--port\", \"8000\"]";
            } else {
                // Check for Flask
                String flaskFile = findPythonFileWithPattern(workspace, "from flask", "import flask");
                if (flaskFile != null) {
                    envLine = "ENV FLASK_APP=" + flaskFile + "\n";
                    cmd = """
                            CMD ["flask", "run", "--host", "0.0.0.0", "--port", "8000"]""";
                } else if (Files.exists(workspace.resolve("main.py"))) {
                    cmd = """
                            CMD ["python", "main.py"]""";
                } else if (Files.exists(workspace.resolve("app.py"))) {
                    cmd = """
                            CMD ["python", "app.py"]""";
                } else {
                    cmd = """
                            CMD ["python", "app.py"]""";
                }
            }
        }

        // Resolve install step
        String installStep;
        if (Files.exists(workspace.resolve("requirements.txt"))) {
            installStep = "RUN pip install --no-cache-dir -r requirements.txt";
            // Add uvicorn if FastAPI detected but not in requirements
            if (cmd.contains("uvicorn")) {
                installStep += " uvicorn";
            }
        } else if (Files.exists(workspace.resolve("pyproject.toml"))) {
            installStep = "RUN pip install --no-cache-dir .";
        } else {
            installStep = "# No requirements file found";
        }

        return """
                FROM python:3.12-slim
                WORKDIR /app
                LABEL preview=true
                COPY requirements.txt* pyproject.toml* ./
                """ + installStep + "\n" +
                "COPY . .\n" +
                "EXPOSE 8000\n" +
                envLine +
                cmd.stripLeading() + "\n";
    }

    // ── Full-stack support (directory-based) ────────────────────────────────

    /**
     * Scan subdirectories for frontend/backend projects.
     * Handles cases where frontend/ and backend/ exist as sibling directories
     * without npm workspaces (e.g., React + Spring Boot, React + Express, React + Python).
     */
    FullStackLayout detectFullStack(Path workspace) {
        String frontendDir = null;
        PreviewProjectType frontendType = null;
        String backendDir = null;
        PreviewProjectType backendType = null;

        try (Stream<Path> dirs = Files.list(workspace)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                String name = dir.getFileName().toString().toLowerCase();

                // Check frontend directories
                if (frontendDir == null && FRONTEND_DIR_NAMES.contains(name)) {
                    PreviewProjectType detected = detectSingleDir(dir);
                    if (detected == PreviewProjectType.NODE_FRONTEND) {
                        frontendDir = dir.getFileName().toString();
                        frontendType = detected;
                    }
                }

                // Check backend directories
                if (backendDir == null && BACKEND_DIR_NAMES.contains(name)) {
                    PreviewProjectType detected = detectSingleDir(dir);
                    if (detected != null && detected != PreviewProjectType.NODE_FRONTEND) {
                        backendDir = dir.getFileName().toString();
                        backendType = detected;
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Failed to scan workspace subdirectories: {}", e.getMessage());
        }

        if (frontendDir == null && backendDir == null) return null;
        return new FullStackLayout(frontendDir, backendDir, frontendType, backendType);
    }

    /**
     * Detect project type for a single subdirectory.
     */
    private PreviewProjectType detectSingleDir(Path dir) {
        // Spring Boot
        Path pomXml = dir.resolve("pom.xml");
        if (Files.exists(pomXml) && fileContainsPattern(pomXml, "spring-boot-starter")) {
            return PreviewProjectType.SPRING_BOOT;
        }

        // Node
        if (Files.exists(dir.resolve("package.json"))) {
            JsonNode pkg = null;
            try {
                pkg = mapper.readTree(dir.resolve("package.json").toFile());
            } catch (IOException ignored) {}

            if (pkg != null) {
                JsonNode deps = pkg.get("dependencies");
                if (deps != null) {
                    for (String backendDep : NODE_BACKEND_DEPS) {
                        if (deps.has(backendDep)) return PreviewProjectType.NODE_BACKEND;
                    }
                }
            }
            return PreviewProjectType.NODE_FRONTEND;
        }

        // Python
        if (Files.exists(dir.resolve("requirements.txt")) || Files.exists(dir.resolve("pyproject.toml"))) {
            return PreviewProjectType.PYTHON;
        }

        return null;
    }

    /**
     * Generate a Dockerfile for full-stack projects with separate frontend/backend directories.
     * Supports: Node+Node, Node+SpringBoot, Node+Python.
     */
    private String fullStackDockerfile(Path workspace, FullStackLayout layout) {
        if (layout.backendType() == PreviewProjectType.SPRING_BOOT) {
            return fullStackNodeSpringBoot(workspace, layout);
        }
        if (layout.backendType() == PreviewProjectType.PYTHON) {
            return fullStackNodePython(workspace, layout);
        }
        // Default: Node + Node
        return fullStackNodeNode(workspace, layout);
    }

    /** Node frontend + Node backend in separate directories (no npm workspaces). */
    private String fullStackNodeNode(Path workspace, FullStackLayout layout) {
        String fe = layout.frontendDir();
        String be = layout.backendDir();
        int backendPort = 3000;

        // Try to detect backend port
        if (be != null) {
            Path beDir = workspace.resolve(be);
            try {
                JsonNode pkg = mapper.readTree(beDir.resolve("package.json").toFile());
                backendPort = detectBackendPort(beDir, pkg);
            } catch (IOException ignored) {}
        }

        var sb = new StringBuilder();
        sb.append("FROM node:20-alpine\nWORKDIR /app\nLABEL preview=true\n");

        // Install frontend deps
        if (fe != null) {
            sb.append("COPY ").append(fe).append("/package*.json ./").append(fe).append("/\n");
            sb.append("RUN cd ").append(fe).append(" && npm install --ignore-scripts && npm rebuild\n");
        }
        // Install backend deps
        if (be != null) {
            sb.append("COPY ").append(be).append("/package*.json ./").append(be).append("/\n");
            sb.append("RUN cd ").append(be).append(" && npm install --ignore-scripts && npm rebuild\n");
        }

        sb.append("COPY . .\n");

        if (fe != null && be != null) {
            sb.append("EXPOSE 5173 ").append(backendPort).append("\n");
            sb.append("CMD [\"sh\", \"-c\", \"(cd /app/").append(be)
              .append(" && npm start 2>&1 | awk '{print \\\"[backend] \\\" $0; fflush()}') & ")
              .append("(cd /app/").append(fe)
              .append(" && npx vite --host 0.0.0.0 --port 5173 2>&1 | awk '{print \\\"[frontend] \\\" $0; fflush()}')\"]\n");
        } else if (fe != null) {
            sb.append("EXPOSE 5173\n");
            sb.append("CMD [\"sh\", \"-c\", \"cd /app/").append(fe).append(" && npx vite --host 0.0.0.0 --port 5173\"]\n");
        } else {
            sb.append("EXPOSE ").append(backendPort).append("\n");
            sb.append("CMD [\"sh\", \"-c\", \"cd /app/").append(be).append(" && npm start\"]\n");
        }

        return sb.toString();
    }

    /** Node frontend + Spring Boot backend. */
    private String fullStackNodeSpringBoot(Path workspace, FullStackLayout layout) {
        String fe = layout.frontendDir();
        String be = layout.backendDir();

        var sb = new StringBuilder();
        // Use JDK image with Node installed
        sb.append("FROM eclipse-temurin:21\nWORKDIR /app\nLABEL preview=true\n");
        sb.append("RUN apt-get update && apt-get install -y maven nodejs npm && rm -rf /var/lib/apt/lists/*\n");

        // Install backend Maven deps first (slow, cached)
        if (be != null) {
            sb.append("COPY ").append(be).append("/pom.xml ./").append(be).append("/\n");
            sb.append("RUN cd ").append(be).append(" && mvn dependency:go-offline -q -B\n");
        }
        // Install frontend Node deps
        if (fe != null) {
            sb.append("COPY ").append(fe).append("/package*.json ./").append(fe).append("/\n");
            sb.append("RUN cd ").append(fe).append(" && npm install --ignore-scripts && npm rebuild\n");
        }

        sb.append("COPY . .\n");
        sb.append("EXPOSE 5173 8080\n");

        if (fe != null && be != null) {
            sb.append("CMD [\"sh\", \"-c\", \"(cd /app/").append(be)
              .append(" && mvn spring-boot:run -DskipTests -q 2>&1 | awk '{print \\\"[backend] \\\" $0; fflush()}') & ")
              .append("(cd /app/").append(fe)
              .append(" && npx vite --host 0.0.0.0 --port 5173 2>&1 | awk '{print \\\"[frontend] \\\" $0; fflush()}')\"]\n");
        } else if (be != null) {
            sb.append("CMD [\"sh\", \"-c\", \"cd /app/").append(be)
              .append(" && mvn spring-boot:run -DskipTests -q\"]\n");
        }

        return sb.toString();
    }

    /** Node frontend + Python backend. */
    private String fullStackNodePython(Path workspace, FullStackLayout layout) {
        String fe = layout.frontendDir();
        String be = layout.backendDir();

        var sb = new StringBuilder();
        // Use Python image with Node installed
        sb.append("FROM python:3.12-slim\nWORKDIR /app\nLABEL preview=true\n");
        sb.append("RUN apt-get update && apt-get install -y nodejs npm && rm -rf /var/lib/apt/lists/*\n");

        // Install backend Python deps
        if (be != null) {
            Path beDir = workspace.resolve(be);
            sb.append("COPY ").append(be).append("/requirements.txt* ").append(be).append("/pyproject.toml* ./").append(be).append("/\n");
            if (Files.exists(beDir.resolve("requirements.txt"))) {
                sb.append("RUN cd ").append(be).append(" && pip install --no-cache-dir -r requirements.txt\n");
            } else if (Files.exists(beDir.resolve("pyproject.toml"))) {
                sb.append("RUN cd ").append(be).append(" && pip install --no-cache-dir .\n");
            }
        }
        // Install frontend Node deps
        if (fe != null) {
            sb.append("COPY ").append(fe).append("/package*.json ./").append(fe).append("/\n");
            sb.append("RUN cd ").append(fe).append(" && npm install --ignore-scripts && npm rebuild\n");
        }

        sb.append("COPY . .\n");
        sb.append("EXPOSE 5173 8000\n");

        // Detect Python backend CMD
        String pythonCmd = "python app.py";
        if (be != null) {
            Path beDir = workspace.resolve(be);
            if (Files.exists(beDir.resolve("manage.py"))) {
                pythonCmd = "python manage.py runserver 0.0.0.0:8000";
            } else {
                String fastApiFile = findPythonFileWithPattern(beDir, "from fastapi", "import fastapi");
                if (fastApiFile != null) {
                    String module = fastApiFile.replace(".py", "");
                    pythonCmd = "uvicorn " + module + ":app --host 0.0.0.0 --port 8000";
                } else {
                    String flaskFile = findPythonFileWithPattern(beDir, "from flask", "import flask");
                    if (flaskFile != null) {
                        pythonCmd = "FLASK_APP=" + flaskFile + " flask run --host 0.0.0.0 --port 8000";
                    } else if (Files.exists(beDir.resolve("main.py"))) {
                        pythonCmd = "python main.py";
                    }
                }
            }
        }

        if (fe != null && be != null) {
            sb.append("CMD [\"sh\", \"-c\", \"(cd /app/").append(be).append(" && ").append(pythonCmd)
              .append(" 2>&1 | awk '{print \\\"[backend] \\\" $0; fflush()}') & ")
              .append("(cd /app/").append(fe)
              .append(" && npx vite --host 0.0.0.0 --port 5173 2>&1 | awk '{print \\\"[frontend] \\\" $0; fflush()}')\"]\n");
        } else if (be != null) {
            sb.append("CMD [\"sh\", \"-c\", \"cd /app/").append(be).append(" && ").append(pythonCmd).append("\"]\n");
        }

        return sb.toString();
    }

    // ── Monorepo support ───────────────────────────────────────────────────

    private boolean isMonorepo(JsonNode pkg, Path workspace) {
        JsonNode workspaces = pkg.get("workspaces");
        if (workspaces == null || !workspaces.isArray() || workspaces.isEmpty()) {
            return false;
        }
        // Verify at least one workspace dir exists with its own package.json
        for (JsonNode ws : workspaces) {
            Path wsDir = workspace.resolve(ws.asText());
            if (Files.exists(wsDir.resolve("package.json"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * For monorepos: determine the dominant project type by scanning sub-packages.
     * If any sub-package has a backend dep, treat as NODE_FRONTEND (preview the frontend).
     * The backend runs alongside it.
     */
    private PreviewProjectType detectMonorepoType(JsonNode rootPkg, Path workspace) {
        boolean hasFrontend = false;
        boolean hasBackend = false;

        for (JsonNode ws : rootPkg.get("workspaces")) {
            Path wsDir = workspace.resolve(ws.asText());
            Path wsPkg = wsDir.resolve("package.json");
            if (!Files.exists(wsPkg)) continue;

            try {
                JsonNode pkg = mapper.readTree(wsPkg.toFile());
                JsonNode deps = pkg.get("dependencies");
                if (deps != null) {
                    // Check for frontend frameworks
                    if (deps.has("react") || deps.has("vue") || deps.has("svelte") ||
                        deps.has("vite") || deps.has("next") || deps.has("@angular/core")) {
                        hasFrontend = true;
                    }
                    // Check for backend frameworks
                    for (String backendDep : NODE_BACKEND_DEPS) {
                        if (deps.has(backendDep)) {
                            hasBackend = true;
                            break;
                        }
                    }
                }
                JsonNode devDeps = pkg.get("devDependencies");
                if (devDeps != null && (devDeps.has("vite") || devDeps.has("@vitejs/plugin-react"))) {
                    hasFrontend = true;
                }
            } catch (IOException e) {
                log.debug("Failed to parse workspace package.json at {}: {}", wsPkg, e.getMessage());
            }
        }

        // If has frontend, preview the frontend (primary port). Backend runs alongside.
        if (hasFrontend) return PreviewProjectType.NODE_FRONTEND;
        if (hasBackend) return PreviewProjectType.NODE_BACKEND;
        return PreviewProjectType.NODE_FRONTEND;
    }

    /**
     * Generate a Dockerfile for monorepo projects.
     * Copies all sub-package.json files, installs with workspaces, starts all services.
     */
    private String monorepoDockerfile(JsonNode rootPkg, Path workspace, PreviewProjectType type) {
        var sb = new StringBuilder();
        sb.append("""
                FROM node:20-alpine
                WORKDIR /app
                LABEL preview=true
                """);

        // Copy root package files
        sb.append("COPY package.json package-lock.json* ./\n");

        // Copy each workspace's package.json
        for (JsonNode ws : rootPkg.get("workspaces")) {
            String wsName = ws.asText();
            Path wsDir = workspace.resolve(wsName);
            if (Files.exists(wsDir.resolve("package.json"))) {
                sb.append("COPY ").append(wsName).append("/package.json ./")
                  .append(wsName).append("/\n");
            }
        }

        // Install — use workspaces flag, and ignore-scripts to prevent recursive install hooks
        sb.append("RUN npm install --ignore-scripts\n");

        // Copy all source code
        sb.append("COPY . .\n");

        // Determine ports and CMD based on workspace structure
        String frontendWs = null;
        String backendWs = null;
        int frontendPort = 5173;
        int backendPort = 3000;

        for (JsonNode ws : rootPkg.get("workspaces")) {
            String wsName = ws.asText();
            Path wsDir = workspace.resolve(wsName);
            Path wsPkg = wsDir.resolve("package.json");
            if (!Files.exists(wsPkg)) continue;

            try {
                JsonNode pkg = mapper.readTree(wsPkg.toFile());
                JsonNode deps = pkg.get("dependencies");
                JsonNode devDeps = pkg.get("devDependencies");

                boolean isFrontend = (deps != null && (deps.has("react") || deps.has("vue") || deps.has("svelte")))
                        || (devDeps != null && (devDeps.has("vite") || devDeps.has("@vitejs/plugin-react")));
                boolean isBackend = false;
                if (deps != null) {
                    for (String backendDep : NODE_BACKEND_DEPS) {
                        if (deps.has(backendDep)) {
                            isBackend = true;
                            break;
                        }
                    }
                }

                if (isFrontend && frontendWs == null) {
                    frontendWs = wsName;
                    // Check vite config for custom port
                    if (Files.exists(wsDir.resolve("vite.config.js")) || Files.exists(wsDir.resolve("vite.config.ts"))) {
                        frontendPort = 5173; // default vite port
                    }
                }
                if (isBackend && backendWs == null) {
                    backendWs = wsName;
                    // Try to detect backend port from server file or package.json main
                    backendPort = detectBackendPort(wsDir, pkg);
                }
            } catch (IOException e) {
                log.debug("Failed to parse workspace package.json: {}", e.getMessage());
            }
        }

        // Expose ports
        if (frontendWs != null && backendWs != null) {
            sb.append("EXPOSE ").append(frontendPort).append(" ").append(backendPort).append("\n");
            sb.append("CMD [\"sh\", \"-c\", \"(npm run dev --prefix ")
              .append(backendWs).append(" 2>&1 | awk '{print \\\"[backend] \\\" $0; fflush()}') & ")
              .append("(npx --prefix ").append(frontendWs)
              .append(" vite --host 0.0.0.0 --port ").append(frontendPort)
              .append(" 2>&1 | awk '{print \\\"[frontend] \\\" $0; fflush()}')\"]\n");
        } else if (frontendWs != null) {
            sb.append("EXPOSE ").append(frontendPort).append("\n");
            sb.append("CMD [\"npx\", \"--prefix\", \"").append(frontendWs)
              .append("\", \"vite\", \"--host\", \"0.0.0.0\", \"--port\", \"")
              .append(frontendPort).append("\"]\n");
        } else if (backendWs != null) {
            sb.append("EXPOSE ").append(backendPort).append("\n");
            sb.append("CMD [\"npm\", \"run\", \"dev\", \"--prefix\", \"").append(backendWs).append("\"]\n");
        } else {
            // Fallback: try root dev script
            sb.append("EXPOSE ").append(type.getContainerPort()).append("\n");
            sb.append("CMD [\"npm\", \"run\", \"dev\"]\n");
        }

        return sb.toString();
    }

    /**
     * Try to detect the port a backend server listens on by scanning its main file.
     */
    private int detectBackendPort(Path wsDir, JsonNode pkg) {
        Path mainFile = null;
        if (pkg.has("main")) {
            mainFile = wsDir.resolve(pkg.get("main").asText());
        }
        if (mainFile == null || !Files.exists(mainFile)) {
            mainFile = wsDir.resolve("server.js");
        }
        if (!Files.exists(mainFile)) {
            mainFile = wsDir.resolve("index.js");
        }
        if (Files.exists(mainFile)) {
            try {
                String content = Files.readString(mainFile);
                // Match patterns like: PORT || 3001, PORT || 3000, listen(3001
                var matcher = java.util.regex.Pattern
                        .compile("(?:PORT\\s*\\|\\|\\s*(\\d{4})|listen\\((\\d{4}))")
                        .matcher(content);
                if (matcher.find()) {
                    String port = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                    return Integer.parseInt(port);
                }
            } catch (IOException e) {
                log.debug("Failed to read backend main file: {}", e.getMessage());
            }
        }
        return 3000;
    }

    // ── Static HTML support ────────────────────────────────────────────────

    private boolean hasStaticHtml(Path workspace) {
        try (Stream<Path> files = Files.list(workspace)) {
            return files.anyMatch(p -> p.getFileName().toString().toLowerCase().endsWith(".html"));
        } catch (IOException e) {
            return false;
        }
    }

    private String staticHtmlDockerfile() {
        return """
                FROM nginx:alpine
                WORKDIR /usr/share/nginx/html
                LABEL preview=true
                COPY . .
                RUN rm -f /etc/nginx/conf.d/default.conf
                RUN echo 'server { listen 8080; root /usr/share/nginx/html; index index.html; }' \
                    > /etc/nginx/conf.d/default.conf
                EXPOSE 8080
                CMD ["nginx", "-g", "daemon off;"]
                """;
    }

    // ── Helper methods ──────────────────────────────────────────────────────

    private JsonNode parsePackageJson(Path workspace) {
        Path file = workspace.resolve("package.json");
        if (!Files.exists(file)) return null;
        try {
            return mapper.readTree(file.toFile());
        } catch (IOException e) {
            log.debug("Failed to parse package.json at {}: {}", file, e.getMessage());
            return null;
        }
    }

    private boolean fileContainsPattern(Path file, String... patterns) {
        try {
            if (Files.size(file) > MAX_FILE_SIZE) return false;
            String content = Files.readString(file);
            for (String pattern : patterns) {
                if (content.contains(pattern)) return true;
            }
        } catch (IOException e) {
            log.debug("Failed to read file {}: {}", file, e.getMessage());
        }
        return false;
    }

    private List<Path> findPythonFiles(Path workspace) {
        try (Stream<Path> stream = Files.list(workspace)) {
            return stream
                    .filter(p -> p.toString().endsWith(".py"))
                    .filter(p -> {
                        try {
                            return Files.size(p) <= MAX_FILE_SIZE;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .limit(MAX_PYTHON_FILES)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Find the first Python file in workspace root that contains any of the given patterns.
     * Returns the filename (e.g. "app.py"), or null if none found.
     */
    private String findPythonFileWithPattern(Path workspace, String... patterns) {
        for (Path pyFile : findPythonFiles(workspace)) {
            if (fileContainsPattern(pyFile, patterns)) {
                return pyFile.getFileName().toString();
            }
        }
        return null;
    }
}
