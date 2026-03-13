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

        return switch (type) {
            case NODE_FRONTEND -> nodeFrontendDockerfile(workspace);
            case NODE_BACKEND -> nodeBackendDockerfile(workspace);
            case SPRING_BOOT -> springBootDockerfile();
            case PYTHON -> pythonDockerfile(workspace);
        };
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
            sb.append("CMD [\"sh\", \"-c\", \"npm run dev --prefix ")
              .append(backendWs).append(" & npx --prefix ").append(frontendWs)
              .append(" vite --host 0.0.0.0 --port ").append(frontendPort).append("\"]\n");
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
