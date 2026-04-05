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

    private static final Set<String> FRONTEND_FRAMEWORK_DEPS = Set.of(
            "react", "vue", "svelte", "@sveltejs/kit", "next", "nuxt",
            "solid-js", "preact", "lit", "angular", "@angular/core"
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
        // 0. Check planner-declared project type in .agent-context/project.json
        Path projectJson = workspace.resolve(".agent-context/project.json");
        if (Files.exists(projectJson)) {
            try {
                JsonNode node = mapper.readTree(projectJson.toFile());
                String declared = node.path("project_type").asText(null);
                if (declared != null && !declared.isBlank() && !"UNKNOWN".equals(declared)) {
                    PreviewProjectType fromPlan = PreviewProjectType.valueOf(declared);
                    // Validate: if planner declares a Node-based type, package.json must exist
                    if (requiresPackageJson(fromPlan) && !Files.exists(workspace.resolve("package.json"))) {
                        log.warn("[Detect] Planner declared {} but no package.json found — falling back to detection", fromPlan);
                    } else {
                        log.info("[Detect] Using planner-declared project type: {}", fromPlan);
                        return fromPlan;
                    }
                }
            } catch (IllegalArgumentException e) {
                log.warn("[Detect] Invalid planner-declared project type in project.json — falling back to detection");
            } catch (IOException e) {
                log.debug("[Detect] Could not read project.json — falling back to file-based detection");
            }
        }

        // 1. Spring Boot — Maven or Gradle (check before package.json in case both exist)
        Path pomXml = workspace.resolve("pom.xml");
        if (Files.exists(pomXml) && fileContainsPattern(pomXml, "spring-boot-starter")) {
            return PreviewProjectType.SPRING_BOOT;
        }
        Path buildGradle = workspace.resolve("build.gradle");
        Path buildGradleKts = workspace.resolve("build.gradle.kts");
        if ((Files.exists(buildGradle) && fileContainsPattern(buildGradle, "spring-boot"))
                || (Files.exists(buildGradleKts) && fileContainsPattern(buildGradleKts, "spring-boot"))) {
            return PreviewProjectType.SPRING_BOOT;
        }

        // 1b. Java CLI
        if (hasJavaFiles(workspace)) {
            return PreviewProjectType.JAVA;
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

                // Check both dependencies and devDependencies for backend + frontend frameworks
                boolean hasBackendDep = false;
                boolean hasFrontendDep = false;
                for (String field : List.of("dependencies", "devDependencies")) {
                    JsonNode deps = pkg.get(field);
                    if (deps != null) {
                        for (String backendDep : NODE_BACKEND_DEPS) {
                            if (deps.has(backendDep)) hasBackendDep = true;
                        }
                        for (String frontendDep : FRONTEND_FRAMEWORK_DEPS) {
                            if (deps.has(frontendDep)) hasFrontendDep = true;
                        }
                        // Also check for vite/webpack as frontend indicators
                        if (deps.has("vite") || deps.has("webpack")) hasFrontendDep = true;
                    }
                }

                // Single-package full-stack: both backend framework + frontend framework
                // e.g. express + react in one package.json with concurrently
                if (hasBackendDep && hasFrontendDep) {
                    log.info("[Detect] Single-package full-stack: backend + frontend deps in same package.json");
                    return PreviewProjectType.FULL_STACK;
                }
                if (hasBackendDep) {
                    return PreviewProjectType.NODE_BACKEND;
                }
            }

            // Root package.json exists but has no backend deps.
            // Before defaulting to NODE_FRONTEND, check if there's a backend/ subdirectory.
            // Common pattern: root package.json (vite/react) + backend/ with express.
            FullStackLayout rootPkgLayout = detectFullStack(workspace);
            if (rootPkgLayout != null && rootPkgLayout.hasBackend()) {
                log.info("[Detect] Root package.json + backend/ subdirectory detected → FULL_STACK");
                return PreviewProjectType.FULL_STACK;
            }

            return PreviewProjectType.NODE_FRONTEND;
        }

        // 2b. Static HTML — check BEFORE loose JS/TS fallback.
        // A static site with script.js must not be misdetected as NODE_BACKEND.
        if (hasStaticHtml(workspace)) {
            return PreviewProjectType.STATIC_HTML;
        }

        // 2c. Bare JS/TS files (no package.json, no HTML) — likely a Node script/server
        if (hasJsOrTsFiles(workspace)) {
            return PreviewProjectType.NODE_BACKEND;
        }

        // 3. Python
        if (Files.exists(workspace.resolve("requirements.txt")) ||
            Files.exists(workspace.resolve("pyproject.toml")) ||
            !findPythonFiles(workspace).isEmpty()) {
            return PreviewProjectType.PYTHON;
        }

        // 3b. Go
        if (Files.exists(workspace.resolve("go.mod"))) {
            return PreviewProjectType.GO;
        }

        // 3c. Rust
        if (Files.exists(workspace.resolve("Cargo.toml"))) {
            return PreviewProjectType.RUST;
        }

        // 3d. Ruby
        if (Files.exists(workspace.resolve("Gemfile"))) {
            return PreviewProjectType.RUBY;
        }

        // 3e. PHP
        if (Files.exists(workspace.resolve("composer.json")) || hasPhpFiles(workspace)) {
            return PreviewProjectType.PHP;
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

        // 5. Static HTML already handled above (before JS/TS fallback)

        return null;
    }

    /**
     * Generate Dockerfile content for the given project type.
     * Does NOT write to disk.
     */
    public String generate(PreviewProjectType type, Path workspace) {
        String dockerfile;

        // Monorepo gets a specialized Dockerfile
        JsonNode pkg = parsePackageJson(workspace);
        if (pkg != null && isMonorepo(pkg, workspace)) {
            dockerfile = monorepoDockerfile(pkg, workspace, type);
        } else if (type == PreviewProjectType.FULL_STACK) {
            // Full-stack gets a multi-service Dockerfile
            FullStackLayout layout = detectFullStack(workspace);
            dockerfile = layout != null
                    ? fullStackDockerfile(workspace, layout)
                    : singlePackageFullStackDockerfile(workspace);
        } else {
            dockerfile = switch (type) {
                case NODE_FRONTEND -> nodeFrontendDockerfile(workspace);
                case NODE_BACKEND -> nodeBackendDockerfile(workspace);
                case SPRING_BOOT -> springBootDockerfile(workspace);
                case JAVA -> cliJavaDockerfile(workspace);
                case PYTHON -> pythonDockerfile(workspace);
                case GO -> goDockerfile(workspace);
                case RUBY -> rubyDockerfile(workspace);
                case PHP -> phpDockerfile(workspace);
                case RUST -> rustDockerfile(workspace);
                case FULL_STACK -> singlePackageFullStackDockerfile(workspace);
                case STATIC_HTML -> staticHtmlDockerfile();
            };
        }

        // Use --legacy-peer-deps to avoid peer dependency failures in generated projects.
        // This is a universal fix that catches all variations of 'npm install' or 'npm ci'
        // across all Dockerfile generators (SinglePackage, FullStack, Monorepo, etc).
        dockerfile = dockerfile.replaceAll("(?m)^(\\s*RUN\\s+.*npm\\s+(?:install|ci))(\\b(?!.*--legacy-peer-deps))", "$1 --legacy-peer-deps");
        return injectNonRootUser(dockerfile, type);
    }

    /**
     * Generate a Cloud Run-compatible Dockerfile.
     * Ensures the container listens on $PORT (Cloud Run requirement).
     * Replaces hardcoded ports with $PORT and adds ENV PORT=8080 default.
     */
    public String generateForCloudRun(PreviewProjectType type, Path workspace) {
        String dockerfile = generate(type, workspace);

        // Cloud Run sets PORT env var automatically via --port flag.
        // Ensure container reads it at runtime. Override any non-8080 PORT to 8080,
        // or add ENV PORT=8080 if not present. Without this, a base Dockerfile with
        // ENV PORT=3000 would ignore the ${PORT:-8080} defaults in CMD.
        // Handles both standalone "ENV PORT=3000" and multi-value "ENV ... PORT=8000".
        if (dockerfile.contains("ENV PORT=") && !dockerfile.contains("ENV PORT=8080")) {
            // Standalone: ENV PORT=3000
            dockerfile = dockerfile.replaceFirst("ENV PORT=\\d+", "ENV PORT=8080");
        } else if (java.util.regex.Pattern.compile("(?<=\\s)PORT=\\d+").matcher(dockerfile).find()
                && !java.util.regex.Pattern.compile("(?<=\\s)PORT=8080").matcher(dockerfile).find()) {
            // Multi-value: ENV FLASK_RUN_HOST=0.0.0.0 FLASK_RUN_PORT=8000 ... PORT=8000
            // Replace ALL port-related env values (FLASK_RUN_PORT, FLASK_PORT, PORT) to 8080
            dockerfile = java.util.regex.Pattern.compile("(?<=\\s)((?:FLASK_RUN_|FLASK_)?PORT)=\\d+")
                    .matcher(dockerfile).replaceAll("$1=8080");
        } else if (!dockerfile.contains("ENV PORT=")
                && !java.util.regex.Pattern.compile("(?<=\\s)PORT=\\d+").matcher(dockerfile).find()) {
            int lastFrom = dockerfile.lastIndexOf("FROM ");
            if (lastFrom >= 0) {
                int endOfLine = dockerfile.indexOf('\n', lastFrom);
                if (endOfLine >= 0) {
                    dockerfile = dockerfile.substring(0, endOfLine + 1)
                            + "ENV PORT=8080\n"
                            + dockerfile.substring(endOfLine + 1);
                }
            }
        }

        // Replace hardcoded ports with $PORT for Cloud Run compatibility.
        // Use shell form (not JSON exec) so $PORT expands at runtime.

        // Multi-service full-stack CMD: only remap frontend (vite) port to Cloud Run's PORT.
        // Backend stays on its native port (8080/8000/3000) since Cloud Run only exposes one port
        // and the frontend is what users see in the preview.
        boolean isMultiServiceCmd = dockerfile.contains("[backend]") && dockerfile.contains("[frontend]");
        if (isMultiServiceCmd) {
            // Only remap the vite --port inside the [frontend] section
            dockerfile = dockerfile.replace("npx vite --host 0.0.0.0 --port 5173",
                    "npx vite --host 0.0.0.0 --port ${PORT:-8080}");
        }

        dockerfile = dockerfile.replace("EXPOSE 5173", "EXPOSE 8080");
        dockerfile = dockerfile.replace("EXPOSE 3000", "EXPOSE 8080");
        dockerfile = dockerfile.replace("EXPOSE 8000", "EXPOSE 8080");

        if (!isMultiServiceCmd) {
            // Vite: --port NNNN → --port ${PORT:-8080}
            dockerfile = dockerfile.replace("--port 5173", "--port ${PORT:-8080}");
            dockerfile = dockerfile.replace("--port 3000", "--port ${PORT:-8080}");
            // Python
            dockerfile = dockerfile.replace("--port 8000", "--port ${PORT:-8080}");
            dockerfile = dockerfile.replace("0.0.0.0:8000", "0.0.0.0:${PORT:-8080}");
        }
        // Vite exec form → shell form so $PORT expands at runtime
        dockerfile = dockerfile.replace(
                "CMD [\"npx\", \"vite\", \"--host\", \"0.0.0.0\", \"--port\", \"5173\"]",
                "CMD npx vite --host 0.0.0.0 --port ${PORT:-8080}");
        // Node serve: -l NNNN → -l ${PORT:-8080}
        dockerfile = dockerfile.replace("\"-l\", \"5173\"", "\"-l\", \"8080\"");
        // Convert serve exec form to shell form so PORT env var is available
        dockerfile = dockerfile.replace(
                "CMD [\"npx\", \"serve\", \"-s\", \".\", \"-l\", \"8080\"]",
                "CMD npx serve -s . -l ${PORT:-8080}");
        // Python exec-form port replacement (single-service only)
        dockerfile = dockerfile.replace("\"--port\", \"8000\"", "\"--port\", \"8080\"");
        // Node backend with exec form: convert to shell form so PORT is respected
        // CMD ["npm", "start"] → CMD PORT=${PORT:-8080} npm start
        dockerfile = dockerfile.replace("CMD [\"npm\", \"start\"]",
                "CMD PORT=${PORT:-8080} npm start");
        dockerfile = dockerfile.replace("CMD [\"npm\", \"run\", \"dev\"]",
                "CMD PORT=${PORT:-8080} npm run dev");
        // Monorepo backend-only: CMD ["npm", "run", "dev", "--prefix", "ws"] → shell form
        dockerfile = java.util.regex.Pattern.compile(
                "CMD \\[\"npm\", \"run\", \"dev\", \"--prefix\", \"([^\"]+)\"\\]")
                .matcher(dockerfile)
                .replaceAll(m -> java.util.regex.Matcher.quoteReplacement("CMD PORT=${PORT:-8080} npm run dev --prefix ")
                        + m.group(1));
        // Node backend with explicit main file
        // Use Matcher.replaceAll with quoteReplacement to avoid ${...} being interpreted as regex group ref
        dockerfile = java.util.regex.Pattern.compile("CMD \\[\"node\", \"([^\"]+)\"\\]")
                .matcher(dockerfile)
                .replaceAll(m -> java.util.regex.Matcher.quoteReplacement("CMD PORT=${PORT:-8080} node ") + m.group(1));
        // Django: exec-form with host:port — must convert to shell form so $PORT expands
        // CMD ["python", "manage.py", "runserver", "0.0.0.0:8000"] → shell form
        dockerfile = dockerfile.replace(
                "CMD [\"python\", \"manage.py\", \"runserver\", \"0.0.0.0:${PORT:-8080}\"]",
                "CMD python manage.py runserver 0.0.0.0:${PORT:-8080}");
        // FastAPI/uvicorn: exec-form with --port — convert to shell form
        dockerfile = java.util.regex.Pattern.compile(
                "CMD \\[\"uvicorn\", \"([^\"]+)\", \"--host\", \"0\\.0\\.0\\.0\", \"--port\", \"8080\"\\]")
                .matcher(dockerfile)
                .replaceAll(m -> java.util.regex.Matcher.quoteReplacement("CMD uvicorn ") + m.group(1)
                        + java.util.regex.Matcher.quoteReplacement(" --host 0.0.0.0 --port ${PORT:-8080}"));
        // Flask: exec-form with --port — convert to shell form
        dockerfile = dockerfile.replace(
                "CMD [\"flask\", \"run\", \"--host\", \"0.0.0.0\", \"--port\", \"8080\"]",
                "CMD flask run --host 0.0.0.0 --port ${PORT:-8080}");
        // Generic Python: exec-form — convert to shell form so PORT env var is available
        dockerfile = dockerfile.replaceAll(
                "CMD \\[\"python\", \"([^\"]+)\"\\]",
                "CMD python $1");
        // Spring Boot: pass SERVER_PORT so non-default-port projects bind correctly.
        // (Spring Boot does NOT read PORT env var — only SERVER_PORT.)
        dockerfile = dockerfile.replace(
                "CMD [\"mvn\", \"spring-boot:run\", \"-DskipTests\", \"-q\"]",
                "CMD SERVER_PORT=${PORT:-8080} mvn spring-boot:run -DskipTests -q");
        // ttyd: replace hardcoded port with $PORT for Cloud Run
        dockerfile = dockerfile.replace("\"ttyd\", \"--port\", \"8080\"",
                "\"sh\", \"-c\", \"ttyd --port ${PORT:-8080}\"");
        // MCP Inspector: replace hardcoded port with $PORT
        dockerfile = dockerfile.replace("--port 8080 --", "--port ${PORT:-8080} --");
        // Multi-service full-stack on Cloud Run: shift backend to non-conflicting port.
        // Frontend (vite) gets PORT (8080, exposed to Cloud Run). Backend moves to 8081.
        if (isMultiServiceCmd) {
            // Spring Boot backend inside sh -c: inject SERVER_PORT=8081
            dockerfile = dockerfile.replace("mvn spring-boot:run -DskipTests -q",
                    "SERVER_PORT=8081 mvn spring-boot:run -DskipTests -q");
            // Python backend: shift 8000 → 8081 (only inside the [backend] section of compound CMD)
            dockerfile = dockerfile.replace("runserver 0.0.0.0:8000", "runserver 0.0.0.0:8081");
            dockerfile = dockerfile.replace("--port 8000", "--port 8081");
            // Node backend: shift 3000 → 8081
            // (Node apps typically read PORT env var, but in compound CMD it's set to 8080 for frontend)
        }
        // Single-package full-stack on Cloud Run: inject --port 8080 into the build-time
        // vite script patch so that vite binds to 8080 (matching Cloud Run's startup probe).
        // The RUN node -e script patches package.json at build time, replacing vite with
        // 'vite --host 0.0.0.0'. For Cloud Run, we extend that to also set the port.
        dockerfile = dockerfile.replace("'vite --host 0.0.0.0'", "'vite --host 0.0.0.0 --port 8080'");
        // Full-stack: Cloud Run only supports one port — use frontend port for preview
        dockerfile = dockerfile.replaceAll("EXPOSE \\d+ \\d+", "EXPOSE 8080");

        // Single-package full-stack on Cloud Run: shift backend to non-conflicting port.
        // Vite is already hardcoded to --port 8080 (patched above). Shift ENV PORT from
        // 8080 to 3001 so the backend (Express/Fastify/etc, which reads PORT) doesn't
        // compete with Vite for port 8080. Cloud Run routes traffic to 8080 → reaches Vite.
        boolean isSinglePkgFullStack = !isMultiServiceCmd
                && dockerfile.contains("dev:all");
        if (isSinglePkgFullStack) {
            dockerfile = dockerfile.replace("ENV PORT=8080", "ENV PORT=3001");
            // Patch vite proxy config to forward API requests to backend on port 3001
            dockerfile = injectBeforeCmd(dockerfile,
                    "RUN node -e \"" +
                    "const fs=require('fs');" +
                    "for(const f of['vite.config.js','vite.config.ts','vite.config.mjs']){" +
                    "  if(fs.existsSync(f)){" +
                    "    let c=fs.readFileSync(f,'utf8');" +
                    "    c=c.replace(/localhost:\\\\d{4}/g,'localhost:3001');" +
                    "    c=c.replace(/127\\\\.0\\\\.0\\\\.1:\\\\d{4}/g,'127.0.0.1:3001');" +
                    "    fs.writeFileSync(f,c);" +
                    "    break;" +
                    "  }" +
                    "}\"\n");
        }
        // Ruby: Sinatra default 4567 → Cloud Run PORT
        dockerfile = dockerfile.replace("EXPOSE 4567", "EXPOSE 8080");
        // Rackup: convert exec form to shell form so ${PORT:-8080} expands at runtime
        dockerfile = dockerfile.replace(
                "CMD [\"bundle\", \"exec\", \"rackup\", \"--host\", \"0.0.0.0\", \"--port\", \"4567\"]",
                "CMD bundle exec rackup --host 0.0.0.0 --port ${PORT:-8080}");
        dockerfile = dockerfile.replace("\"--port\", \"4567\"", "\"--port\", \"${PORT:-8080}\"");
        // Go: already uses 8080, just ensure shell form for PORT support
        // PHP: built-in server port
        dockerfile = dockerfile.replace("\"php\", \"-S\", \"0.0.0.0:8080\"",
                "\"sh\", \"-c\", \"php -S 0.0.0.0:${PORT:-8080}\"");

        // Vite 5.4+ blocks requests from unrecognised hostnames (e.g. *.a.run.app).
        // Inject a build-time patch that sets server.allowedHosts in the vite config.
        // ONLY for project types that use Vite (Node.js frontend/full-stack).
        // Running `node -e` on non-Node images (e.g. eclipse-temurin) causes exit 127.
        boolean usesVite = type == PreviewProjectType.NODE_FRONTEND
                || type == PreviewProjectType.FULL_STACK;
        if (usesVite) {
        // Patterns tried in order:
        //   1. server: {           → prepend allowedHosts:true
        //   2. export default {    → prepend server:{allowedHosts:true}
        //   3. defineConfig({      → prepend server:{allowedHosts:true}
        //   4. => ({               → arrow function in defineConfig (e.g. ({ mode }) => ({...}))
        //   5. plugins:            → fallback: prepend server config before plugins array
        dockerfile = injectBeforeCmd(dockerfile,
                "RUN node -e \"" +
                "const fs=require('fs');" +
                "for(const f of['vite.config.js','vite.config.ts','vite.config.mjs']){" +
                "  if(fs.existsSync(f)){" +
                "    let c=fs.readFileSync(f,'utf8');" +
                "    if(!c.includes('allowedHosts')){" +
                "      c=c.replace(/server\\\\s*:\\\\s*\\\\{/,'server:{allowedHosts:true,');" +
                "      if(!c.includes('allowedHosts'))c=c.replace(/export\\\\s+default\\\\s*\\\\{/,'export default{server:{allowedHosts:true},');" +
                "      if(!c.includes('allowedHosts'))c=c.replace(/defineConfig\\\\s*\\\\(\\\\s*\\\\{/,'defineConfig({server:{allowedHosts:true},');" +
                "      if(!c.includes('allowedHosts'))c=c.replace(/=>\\\\s*\\\\(\\\\s*\\\\{/,'=>({server:{allowedHosts:true},');" +
                "      if(!c.includes('allowedHosts'))c=c.replace(/plugins\\\\s*:/,'server:{allowedHosts:true},plugins:');" +
                "      fs.writeFileSync(f,c);" +
                "    }" +
                "    break;" +
                "  }" +
                "}\"\n");
        } // end usesVite

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
                .env
                .env.*
                .env.local
                .env.production
                credentials.json
                service-account.json
                key.json
                keyfile.json
                secrets.yml
                secrets.yaml
                """;
    }

    // ── Security: inject non-root user ─────────────────────────────────────

    /**
     * Injects a non-root USER directive before the final CMD/ENTRYPOINT in the Dockerfile.
     * Detects Alpine vs Debian base images for correct user-creation syntax.
     * STATIC_HTML (nginx) uses the built-in nginx user.
     */
    private String injectNonRootUser(String dockerfile, PreviewProjectType type) {
        if (dockerfile == null || dockerfile.isBlank()) return dockerfile;
        // Already has a USER directive — skip
        if (dockerfile.contains("\nUSER ")) return dockerfile;

        // STATIC_HTML uses nginx image which has its own user
        if (type == PreviewProjectType.STATIC_HTML) {
            return injectBeforeCmd(dockerfile, "USER nginx\n");
        }

        // Detect Alpine vs Debian/Ubuntu base from FROM line
        boolean isAlpine = dockerfile.lines()
                .filter(l -> l.startsWith("FROM "))
                .anyMatch(l -> l.contains("-alpine"));

        String userBlock;
        if (isAlpine) {
            userBlock = "RUN addgroup -S app && adduser -S app -G app && chown -R app:app /app\nUSER app\n";
        } else {
            userBlock = "RUN groupadd -r app && useradd -r -g app -s /sbin/nologin app && chown -R app:app /app\nUSER app\n";
        }

        return injectBeforeCmd(dockerfile, userBlock);
    }

    /** Inserts the given block just before the last CMD or ENTRYPOINT line. */
    private String injectBeforeCmd(String dockerfile, String block) {
        // Find the last CMD or ENTRYPOINT line
        int lastCmd = dockerfile.lastIndexOf("\nCMD ");
        int lastEntry = dockerfile.lastIndexOf("\nENTRYPOINT ");
        int insertPoint = Math.max(lastCmd, lastEntry);
        if (insertPoint < 0) {
            // No CMD found — append at end
            return dockerfile + "\n" + block;
        }
        // Insert before the CMD/ENTRYPOINT line (after the preceding newline)
        return dockerfile.substring(0, insertPoint + 1) + block + dockerfile.substring(insertPoint + 1);
    }

    // ── Private Dockerfile generators ───────────────────────────────────────

    private String nodeFrontendDockerfile(Path workspace) {
        String cmd;
        boolean hasViteConfig = Files.exists(workspace.resolve("vite.config.js"))
                || Files.exists(workspace.resolve("vite.config.ts"));
        JsonNode pkg = parsePackageJson(workspace);
        boolean hasDevScript = pkg != null && pkg.has("scripts")
                && pkg.get("scripts").has("dev");

        if (hasViteConfig || hasDevScript) {
            // Always use vite with explicit --host and --port to ensure container accessibility
            cmd = "CMD [\"npx\", \"vite\", \"--host\", \"0.0.0.0\", \"--port\", \"5173\"]";
        } else {
            cmd = """
                    RUN npm install -g serve
                    CMD ["npx", "serve", "-s", ".", "-l", "5173"]""";
        }

        // Ensure vite and common React dependencies are installed at build time
        String viteInstall = "";
        if (cmd.contains("vite")) {
            StringBuilder missing = new StringBuilder();
            if (!fileContainsPattern(workspace.resolve("package.json"), "\"vite\"")) missing.append(" vite");
            if (!fileContainsPattern(workspace.resolve("package.json"), "\"@vitejs/plugin-react\"")) missing.append(" @vitejs/plugin-react");
            if (!fileContainsPattern(workspace.resolve("package.json"), "\"react\"")) missing.append(" react");
            if (!fileContainsPattern(workspace.resolve("package.json"), "\"react-dom\"")) missing.append(" react-dom");
            if (!fileContainsPattern(workspace.resolve("package.json"), "\"prop-types\"")) missing.append(" prop-types");
            if (!fileContainsPattern(workspace.resolve("package.json"), "\"lucide-react\"")) missing.append(" lucide-react");
            if (!fileContainsPattern(workspace.resolve("package.json"), "\"clsx\"")) missing.append(" clsx");
            if (!fileContainsPattern(workspace.resolve("package.json"), "\"tailwind-merge\"")) missing.append(" tailwind-merge");

            if (missing.length() > 0) {
                viteInstall = " && npm install -D" + missing.toString() + " --legacy-peer-deps";
            }
        }

        return """
                FROM node:20-alpine
                WORKDIR /app
                LABEL preview=true
                COPY package*.json ./
                RUN npm install --legacy-peer-deps""" + viteInstall + "\n" +
                "COPY . .\n" +
                "EXPOSE 5173\n" +
                cmd.stripLeading() + "\n";
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
        } else {
            // Scan for common Node entry points
            String entryFile = findNodeEntryPoint(workspace);
            cmd = "CMD [\"node\", \"" + entryFile + "\"]";
        }

        return """
                FROM node:20-alpine
                WORKDIR /app
                LABEL preview=true
                ENV HOST=0.0.0.0
                ENV PORT=3000
                COPY package*.json ./
                RUN npm install --legacy-peer-deps
                COPY . .
                EXPOSE 3000
                """ + cmd.stripLeading() + "\n";
    }

    /**
     * Single-package full-stack: both frontend (React/Vue/etc) and backend (Express/etc)
     * in the same package.json, typically run via "concurrently" or similar.
     * Uses "npm run dev" if available, otherwise starts backend + vite in parallel.
     */
    /**
     * Single-package full-stack: both frontend (React/Vue/etc) and backend (Express/etc)
     * in the same package.json, typically run via "concurrently" or similar.
     * Uses "npm run dev:full" if available, falling back to "dev" or "start".
     */
    private String singlePackageFullStackDockerfile(Path workspace) {
        JsonNode pkg = parsePackageJson(workspace);
        boolean hasViteConfig = Files.exists(workspace.resolve("vite.config.js"))
                || Files.exists(workspace.resolve("vite.config.ts"));

        boolean usesVite = hasViteConfig;

        if (pkg != null && pkg.has("scripts")) {
            JsonNode scripts = pkg.get("scripts");
            // Prioritize scripts that likely run both FE and BE
            List<String> fullStackCandidates = List.of("dev:full", "start:full", "server:full", "all", "dev", "start");
            for (String candidate : fullStackCandidates) {
                if (scripts.has(candidate)) {
                    String scriptVal = scripts.get(candidate).asText("");
                    if (scriptVal.contains("vite")) usesVite = true;
                    break;
                }
            }
        }

        // Ensure vite, concurrently, and common React dependencies are available if it's a Vite project
        String viteInstall = "";
        if (usesVite) {
            StringBuilder missing = new StringBuilder();
            if (!fileContainsPattern(workspace.resolve("package.json"), "\"vite\"")) missing.append(" vite");
            if (!fileContainsPattern(workspace.resolve("package.json"), "\"@vitejs/plugin-react\"")) missing.append(" @vitejs/plugin-react");
            if (!fileContainsPattern(workspace.resolve("package.json"), "\"react\"")) missing.append(" react");
            if (!fileContainsPattern(workspace.resolve("package.json"), "\"react-dom\"")) missing.append(" react-dom");
            if (!fileContainsPattern(workspace.resolve("package.json"), "\"prop-types\"")) missing.append(" prop-types");
            if (!fileContainsPattern(workspace.resolve("package.json"), "\"lucide-react\"")) missing.append(" lucide-react");
            if (!fileContainsPattern(workspace.resolve("package.json"), "\"clsx\"")) missing.append(" clsx");
            if (!fileContainsPattern(workspace.resolve("package.json"), "\"tailwind-merge\"")) missing.append(" tailwind-merge");
            
            // Mandate concurrently for full-stack projects to ensure both FE and BE start
            if (!fileContainsPattern(workspace.resolve("package.json"), "\"concurrently\"")) missing.append(" concurrently");

            if (missing.length() > 0) {
                viteInstall = " && npm install -D" + missing.toString() + " --legacy-peer-deps";
            }
        }

        return """
                FROM node:20-alpine
                WORKDIR /app
                LABEL preview=true
                ENV HOST=0.0.0.0
                COPY package*.json ./
                RUN npm install --legacy-peer-deps""" + viteInstall + "\n" +
                "COPY . .\n" +
                // Patch package.json:
                // 1. Inject --host 0.0.0.0 into any vite script
                // 2. If it's a full-stack project but 'dev' only runs vite, create 'dev:all' that runs both
                "RUN node -e \"" +
                "const fs=require('fs');const p=JSON.parse(fs.readFileSync('package.json','utf8'));" +
                "if(p.scripts){" +
                "  for(const[k,v]of Object.entries(p.scripts)){" +
                "    if(typeof v==='string'&&/\\\\bvite\\\\b/.test(v)&&!v.includes('--host')){" +
                "      p.scripts[k]=v.replace(/\\\\bvite\\\\b/g,'vite --host 0.0.0.0')" +
                "    }" +
                "  }" +
                "  const hasServer=p.scripts.server||p.scripts.backend||fs.existsSync('server/server.js')||fs.existsSync('server.js');" +
                "  const runsBoth=Object.values(p.scripts).some(v=>v.includes('concurrently')||(v.includes('vite')&&v.includes('node')));" +
                "  if(hasServer && !runsBoth){" +
                "    const serverCmd=p.scripts.server?'npm run server':(p.scripts.backend?'npm run backend':(fs.existsSync('server/server.js')?'node server/server.js':'node server.js'));" +
                "    p.scripts['dev:all']='concurrently \\\"npm run dev\\\" \\\"'+serverCmd+'\\\"';" +
                "  }" +
                "}" +
                "fs.writeFileSync('package.json',JSON.stringify(p,null,2));\"\n" +
                "EXPOSE 5173 3001\n" +
                "CMD [\"sh\", \"-c\", \"node -e \\\"var s=JSON.parse(require('fs').readFileSync('package.json')).scripts;process.exit(s['dev:all']?0:1)\\\" && npm run dev:all || npm run dev\"]\n";
    }

    private String springBootDockerfile(Path workspace) {
        boolean isGradle = Files.exists(workspace.resolve("build.gradle"))
                || Files.exists(workspace.resolve("build.gradle.kts"));

        if (isGradle) {
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
                RUN mvn compile -DskipTests -q -B || true
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
        } else if (hasDependency(workspace, "fastapi")) {
            // FastAPI — check requirements.txt first, then scan source files
            String fastApiFile = findPythonFileWithPattern(workspace, "from fastapi", "import fastapi", "FastAPI(");
            String module = (fastApiFile != null) ? fastApiFile.replace(".py", "") : "main";
            cmd = "CMD [\"uvicorn\", \"" + module + ":app\", \"--host\", \"0.0.0.0\", \"--port\", \"8000\"]";
        } else if (hasDependency(workspace, "flask")) {
            // Flask — check requirements.txt first, then scan source files
            String flaskFile = findPythonFileWithPattern(workspace, "from flask", "import flask", "Flask(");
            if (flaskFile != null) {
                envLine = "ENV FLASK_APP=" + flaskFile + "\n";
            }
            cmd = """
                    CMD ["flask", "run", "--host", "0.0.0.0", "--port", "8000"]""";
        } else {
            // Check source files as fallback (framework not in requirements)
            String fastApiFile = findPythonFileWithPattern(workspace, "from fastapi", "import fastapi");
            if (fastApiFile != null) {
                String module = fastApiFile.replace(".py", "");
                cmd = "CMD [\"uvicorn\", \"" + module + ":app\", \"--host\", \"0.0.0.0\", \"--port\", \"8000\"]";
            } else {
                String flaskFile = findPythonFileWithPattern(workspace, "from flask", "import flask");
                if (flaskFile != null) {
                    envLine = "ENV FLASK_APP=" + flaskFile + "\n";
                    cmd = """
                            CMD ["flask", "run", "--host", "0.0.0.0", "--port", "8000"]""";
                } else {
                    String entryFile = findPythonEntryPoint(workspace);
                    cmd = "CMD [\"python\", \"" + entryFile + "\"]";
                }
            }
        }

        // Safety net: set ALL common host/port env vars for Python web frameworks.
        // Different apps read different env var names:
        //   flask run CLI     → FLASK_RUN_HOST, FLASK_RUN_PORT
        //   app.run()         → FLASK_HOST, FLASK_PORT (custom, but common pattern)
        //   uvicorn           → HOST, PORT
        //   generic           → HOST, PORT
        envLine += "ENV FLASK_RUN_HOST=0.0.0.0 FLASK_RUN_PORT=8000 " +
                   "FLASK_HOST=0.0.0.0 FLASK_PORT=8000 " +
                   "HOST=0.0.0.0 PORT=8000\n";

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

        // If we found a backend subdir but no frontend subdir, check if the root itself
        // is the frontend (has vite/react/vue in root package.json). Use "." as frontendDir.
        if (backendDir != null && frontendDir == null) {
            Path rootPkg = workspace.resolve("package.json");
            if (Files.exists(rootPkg)) {
                try {
                    JsonNode pkg = mapper.readTree(rootPkg.toFile());
                    boolean rootIsFrontend = false;
                    for (String field : List.of("dependencies", "devDependencies")) {
                        JsonNode deps = pkg != null ? pkg.get(field) : null;
                        if (deps != null) {
                            for (String feDep : FRONTEND_FRAMEWORK_DEPS) {
                                if (deps.has(feDep)) { rootIsFrontend = true; break; }
                            }
                            if (deps.has("vite") || deps.has("webpack")) rootIsFrontend = true;
                        }
                        if (rootIsFrontend) break;
                    }
                    if (rootIsFrontend) {
                        frontendDir = ".";
                        frontendType = PreviewProjectType.NODE_FRONTEND;
                        log.info("[Detect] Root is frontend (vite/react), backend in {}/", backendDir);
                    }
                } catch (IOException ignored) {}
            }
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

        // Node — check for own package.json with backend deps
        if (Files.exists(dir.resolve("package.json"))) {
            JsonNode pkg = null;
            try {
                pkg = mapper.readTree(dir.resolve("package.json").toFile());
            } catch (IOException ignored) {}

            if (pkg != null) {
                for (String field : List.of("dependencies", "devDependencies")) {
                    JsonNode deps = pkg.get(field);
                    if (deps != null) {
                        for (String backendDep : NODE_BACKEND_DEPS) {
                            if (deps.has(backendDep)) return PreviewProjectType.NODE_BACKEND;
                        }
                    }
                }
            }
            return PreviewProjectType.NODE_FRONTEND;
        }

        // Node — no package.json but has JS/TS files (backend code lives in subdir,
        // deps are in root package.json). Scan for server-like files.
        String dirName = dir.getFileName().toString().toLowerCase();
        if (BACKEND_DIR_NAMES.contains(dirName) && hasJsOrTsFiles(dir)) {
            // Check for server indicators: express patterns, http.createServer, app.listen
            try (Stream<Path> files = Files.walk(dir, 2)) {
                boolean hasServerCode = files
                        .filter(f -> f.toString().endsWith(".js") || f.toString().endsWith(".ts"))
                        .anyMatch(f -> fileContainsPattern(f,
                                "express\\(|createServer|app\\.listen|fastify|new Hono|Koa\\("));
                if (hasServerCode) return PreviewProjectType.NODE_BACKEND;
            } catch (IOException ignored) {}
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
        boolean feIsRoot = ".".equals(fe);   // frontend lives in project root
        int backendPort = 3000;

        // Try to detect backend port
        if (be != null) {
            Path beDir = workspace.resolve(be);
            try {
                JsonNode bePkg = mapper.readTree(beDir.resolve("package.json").toFile());
                backendPort = detectBackendPort(beDir, bePkg);
            } catch (IOException ignored) {}
        }

        var sb = new StringBuilder();
        sb.append("FROM node:20-alpine\nWORKDIR /app\nLABEL preview=true\n");

        // Install frontend deps + ensure vite is available for dev server
        if (fe != null) {
            if (feIsRoot) {
                // Frontend is at root — install root deps
                sb.append("COPY package*.json ./\n");
                sb.append("RUN npm install");
            } else {
                sb.append("COPY ").append(fe).append("/package*.json ./").append(fe).append("/\n");
                sb.append("RUN cd ").append(fe).append(" && npm install");
            }
            // If vite isn't in dependencies, install it explicitly
            Path fePkg = feIsRoot ? workspace.resolve("package.json")
                                  : workspace.resolve(fe).resolve("package.json");
            if (Files.exists(fePkg) && !fileContainsPattern(fePkg, "\"vite\"")) {
                sb.append(" && npm install vite");
            }
            sb.append("\n");
        }
        // Install backend deps (only if backend has its own package.json)
        if (be != null) {
            Path bePkgPath = workspace.resolve(be).resolve("package.json");
            if (Files.exists(bePkgPath)) {
                sb.append("COPY ").append(be).append("/package*.json ./").append(be).append("/\n");
                sb.append("RUN cd ").append(be).append(" && npm install\n");
            }
        }

        sb.append("COPY . .\n");

        // Determine frontend cmd path — root or subdir
        String feCmd = feIsRoot ? "/app" : "/app/" + fe;

        if (fe != null && be != null) {
            // Determine backend start command — use npm start if package.json exists, else node
            String beStartCmd;
            Path bePkgPath = workspace.resolve(be).resolve("package.json");
            if (Files.exists(bePkgPath)) {
                beStartCmd = "cd /app/" + be + " && npm start";
            } else {
                // Backend has no package.json — find entry point and run with node
                String entry = findNodeEntryPointInDir(workspace.resolve(be));
                beStartCmd = "cd /app/" + be + " && node " + entry;
            }

            sb.append("EXPOSE 5173 ").append(backendPort).append("\n");
            sb.append("CMD [\"sh\", \"-c\", \"(").append(beStartCmd)
              .append(" 2>&1 | awk '{print \\\"[backend] \\\" $0; fflush()}') & ")
              .append("(cd ").append(feCmd)
              .append(" && npx vite --host 0.0.0.0 --port 5173 2>&1 | awk '{print \\\"[frontend] \\\" $0; fflush()}')\"]\n");
        } else if (fe != null) {
            sb.append("EXPOSE 5173\n");
            sb.append("CMD [\"sh\", \"-c\", \"cd ").append(feCmd).append(" && npx vite --host 0.0.0.0 --port 5173\"]\n");
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
            sb.append("RUN cd ").append(fe).append(" && npm install\n");
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
            sb.append("RUN cd ").append(fe).append(" && npm install\n");
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
                    } else {
                        pythonCmd = "python " + findPythonEntryPoint(beDir);
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

        // Install all workspace deps — do NOT use --ignore-scripts here since
        // workspace binary linking (e.g., vite, tsc) requires postinstall hooks
        sb.append("RUN npm install\n");

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

    private boolean hasPhpFiles(Path workspace) {
        try (Stream<Path> files = Files.list(workspace)) {
            return files.anyMatch(p -> p.getFileName().toString().toLowerCase().endsWith(".php"));
        } catch (IOException e) {
            return false;
        }
    }

    private String findRubyEntryPoint(Path workspace) {
        // Priority order: app.rb, server.rb, main.rb, then first .rb file
        for (String candidate : List.of("app.rb", "server.rb", "main.rb")) {
            if (Files.exists(workspace.resolve(candidate))) {
                return candidate;
            }
        }
        try (Stream<Path> files = Files.list(workspace)) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(".rb"))
                    .map(p -> p.getFileName().toString())
                    .findFirst()
                    .orElse("app.rb");
        } catch (IOException e) {
            return "app.rb";
        }
    }

    private String goDockerfile(Path workspace) {
        return """
                FROM golang:1.22-alpine
                WORKDIR /app
                LABEL preview=true
                ENV HOST=0.0.0.0
                ENV PORT=8080
                COPY go.mod go.sum* ./
                RUN go mod download 2>/dev/null || true
                COPY . .
                RUN go build -o server . 2>/dev/null || true
                EXPOSE 8080
                CMD ["sh", "-c", "if [ -f server ]; then ./server; else go run .; fi"]
                """;
    }

    private String rubyDockerfile(Path workspace) {
        // Detect entry file: app.rb, server.rb, main.rb, config.ru, or first .rb
        String entryFile = findRubyEntryPoint(workspace);
        boolean hasBundler = Files.exists(workspace.resolve("Gemfile"));

        String installStep = hasBundler
                ? "RUN apk add --no-cache build-base && bundle install"
                : "# No Gemfile found";

        // If config.ru exists, use rackup
        if (Files.exists(workspace.resolve("config.ru"))) {
            return """
                    FROM ruby:3.2-alpine
                    WORKDIR /app
                    LABEL preview=true
                    COPY Gemfile* ./
                    """ + installStep + "\n" + """
                    COPY . .
                    EXPOSE 4567
                    CMD ["bundle", "exec", "rackup", "--host", "0.0.0.0", "--port", "4567"]
                    """;
        }

        return """
                FROM ruby:3.2-alpine
                WORKDIR /app
                LABEL preview=true
                ENV HOST=0.0.0.0
                ENV PORT=4567
                COPY Gemfile* ./
                """ + installStep + "\n" + """
                COPY . .
                EXPOSE 4567
                CMD ["ruby", \"""" + entryFile + "\"]\n";
    }

    private String phpDockerfile(Path workspace) {
        boolean hasComposer = Files.exists(workspace.resolve("composer.json"));
        String installStep = hasComposer
                ? "RUN curl -sS https://getcomposer.org/installer | php && php composer.phar install --no-dev 2>/dev/null || true"
                : "# No composer.json found";

        return """
                FROM php:8.2-cli
                WORKDIR /app
                LABEL preview=true
                COPY . .
                """ + installStep + "\n" + """
                EXPOSE 8080
                CMD ["php", "-S", "0.0.0.0:8080"]
                """;
    }

    private String rustDockerfile(Path workspace) {
        return """
                FROM rust:1.77-slim
                RUN apt-get update && apt-get install -y pkg-config libssl-dev && rm -rf /var/lib/apt/lists/*
                WORKDIR /app
                LABEL preview=true
                COPY Cargo.toml Cargo.lock* ./
                RUN mkdir src && echo "fn main() {}" > src/main.rs && cargo build --release 2>/dev/null || true && rm -rf src
                COPY . .
                RUN cargo build --release 2>/dev/null || true
                EXPOSE 8080
                CMD ["sh", "-c", "bin=$(find target/release -maxdepth 1 -type f -executable | head -1) && if [ -n \\"$bin\\" ]; then exec \\"$bin\\"; else cargo run --release; fi"]
                """;
    }

    private String staticHtmlDockerfile() {
        // Use echo with proper escaping. printf '\$uri' produces literal \$uri (bug).
        // echo '...$uri...' with single quotes preserves $uri correctly.
        // Pre-chown nginx dirs so nginx can run with cap_drop:ALL (no runtime chown needed).
        return """
                FROM nginx:alpine
                WORKDIR /usr/share/nginx/html
                LABEL preview=true
                COPY . .
                RUN rm -f /etc/nginx/conf.d/default.conf \
                    && echo 'server { listen 8080; root /usr/share/nginx/html; index index.html index.htm; location / { try_files $uri $uri/ /index.html =404; } }' \
                       > /etc/nginx/conf.d/default.conf \
                    && chown -R nginx:nginx /var/cache/nginx /var/log/nginx \
                    && touch /var/run/nginx.pid && chown nginx:nginx /var/run/nginx.pid
                EXPOSE 8080
                CMD ["nginx", "-g", "daemon off;"]
                """;
    }

    // ── Preview mode detection ─────────────────────────────────────────────

    /**
     * Detect how a project should be previewed: as a web app (direct HTTP),
     * a CLI app (wrapped by ttyd), or an MCP server (wrapped by Inspector).
     */
    public PreviewMode detectMode(PreviewProjectType type, Path workspace) {
        if (isMcpProject(type, workspace)) return PreviewMode.MCP_INSPECTOR;
        if (isCliProject(type, workspace)) return PreviewMode.CLI_TERMINAL;
        
        if (type == PreviewProjectType.FULL_STACK || type == PreviewProjectType.NODE_FRONTEND || type == PreviewProjectType.STATIC_HTML) {
            return PreviewMode.FULL_STACK;
        }
        return PreviewMode.REST;
    }

    private boolean isMcpProject(PreviewProjectType type, Path workspace) {
        // Node: check package.json for MCP SDK
        if (type == PreviewProjectType.NODE_BACKEND || type == PreviewProjectType.NODE_FRONTEND) {
            JsonNode pkg = parsePackageJson(workspace);
            if (pkg != null) {
                for (String field : List.of("dependencies", "devDependencies")) {
                    JsonNode deps = pkg.get(field);
                    if (deps != null && (deps.has("@modelcontextprotocol/sdk") || deps.has("mcp"))) {
                        return true;
                    }
                }
            }
        }
        // Python: check requirements.txt / pyproject.toml for mcp package
        if (type == PreviewProjectType.PYTHON) {
            return hasDependency(workspace, "mcp") || hasDependency(workspace, "modelcontextprotocol");
        }
        return false;
    }

    private boolean isCliProject(PreviewProjectType type, Path workspace) {
        return switch (type) {
            case PYTHON -> {
                // Django project → not CLI
                if (Files.exists(workspace.resolve("manage.py"))) yield false;
                // Web framework in deps → not CLI
                if (hasDependency(workspace, "fastapi") || hasDependency(workspace, "flask")
                        || hasDependency(workspace, "uvicorn") || hasDependency(workspace, "django")) {
                    yield false;
                }
                // Web framework in source → not CLI
                if (findPythonFileWithPattern(workspace, "from fastapi", "import fastapi",
                        "from flask", "import flask", "from django") != null) {
                    yield false;
                }
                yield true;
            }
            case GO -> !hasGoWebImports(workspace);
            case RUST -> {
                Path cargo = workspace.resolve("Cargo.toml");
                if (Files.exists(cargo)) {
                    yield !fileContainsPattern(cargo, "actix-web", "rocket", "axum", "warp", "hyper");
                }
                yield true;
            }
            case RUBY -> {
                if (Files.exists(workspace.resolve("config.ru"))) yield false;
                Path gemfile = workspace.resolve("Gemfile");
                if (Files.exists(gemfile)) {
                    yield !fileContainsPattern(gemfile, "sinatra", "rails", "rack", "roda", "hanami");
                }
                yield true;
            }
            case PHP -> {
                Path composer = workspace.resolve("composer.json");
                if (Files.exists(composer)) {
                    yield !fileContainsPattern(composer, "laravel", "symfony", "slim/slim");
                }
                yield true;
            }
            case NODE_BACKEND -> {
                JsonNode pkg = parsePackageJson(workspace);
                if (pkg != null) {
                    for (String field : List.of("dependencies", "devDependencies")) {
                        JsonNode deps = pkg.get(field);
                        if (deps != null) {
                            for (String backendDep : NODE_BACKEND_DEPS) {
                                if (deps.has(backendDep)) yield false;
                            }
                        }
                    }
                }
                yield true; // No web framework detected -> CLI script
            }
            case NODE_FRONTEND, SPRING_BOOT, FULL_STACK, STATIC_HTML -> false;
            case JAVA -> true;
        };
    }

    private boolean hasGoWebImports(Path workspace) {
        try (Stream<Path> files = Files.list(workspace)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".go")).limit(20).toList()) {
                if (fileContainsPattern(f, "net/http", "gin-gonic", "gofiber/fiber",
                        "labstack/echo", "go-chi/chi", "gorilla/mux")) {
                    return true;
                }
            }
        } catch (IOException e) { /* ignore */ }
        // Also check cmd/ and internal/ subdirectories
        for (String sub : new String[]{"cmd", "internal", "pkg"}) {
            Path subDir = workspace.resolve(sub);
            if (Files.isDirectory(subDir)) {
                try (Stream<Path> files = Files.walk(subDir, 2)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".go")).limit(20).toList()) {
                        if (fileContainsPattern(f, "net/http", "gin-gonic", "gofiber/fiber",
                                "labstack/echo", "go-chi/chi", "gorilla/mux")) {
                            return true;
                        }
                    }
                } catch (IOException e) { /* ignore */ }
            }
        }
        return false;
    }

    // ── Mode-aware Dockerfile generation ─────────────────────────────────────

    /**
     * Generate Dockerfile content for the given project type and preview mode.
     */
    public String generate(PreviewProjectType type, Path workspace, PreviewMode mode) {
        if (mode == PreviewMode.CLI_TERMINAL) return cliDockerfile(type, workspace);
        if (mode == PreviewMode.MCP_INSPECTOR) return mcpDockerfile(type, workspace);
        return generate(type, workspace); // existing WEB behaviour
    }

    private String cliDockerfile(PreviewProjectType type, Path workspace) {
        return switch (type) {
            case PYTHON -> cliPythonDockerfile(workspace);
            case JAVA -> cliJavaDockerfile(workspace);
            case NODE_BACKEND -> cliNodeDockerfile(workspace);
            case GO -> cliGoDockerfile();
            case RUST -> cliRustDockerfile();
            case RUBY -> cliRubyDockerfile(workspace);
            case PHP -> cliPhpDockerfile(workspace);
            default -> generate(type, workspace); // fallback to web
        };
    }

    private String cliNodeDockerfile(Path workspace) {
        String entryFile = findNodeEntryPoint(workspace);
        String installStep = Files.exists(workspace.resolve("package.json")) ? "RUN npm install" : "# No package.json";
        return """
                FROM node:20-alpine
                WORKDIR /app
                LABEL preview=true
                RUN apk add --no-cache ttyd
                COPY package*.json* ./
                """ + installStep + "\n" + """
                COPY . .
                EXPOSE 8080
                CMD ["ttyd", "--port", "8080", "--writable", "node", \"""" + entryFile + "\"]\n";
    }

    private String cliJavaDockerfile(Path workspace) {
        // Find the java file containing 'public static void main'
        String mainClass = "Main";
        try (Stream<Path> stream = Files.walk(workspace, 3)) {
            Path mainFile = stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> fileContainsPattern(p, "public static void main"))
                    .findFirst().orElse(null);
            if (mainFile != null) {
                // If it's a single file at root, mainClass is just the name without .java
                String filename = mainFile.getFileName().toString();
                mainClass = filename.substring(0, filename.length() - 5);
            }
        } catch (IOException e) { /* ignore */ }
        
        return """
                FROM eclipse-temurin:21
                WORKDIR /app
                LABEL preview=true
                RUN apt-get update && apt-get install -y wget && rm -rf /var/lib/apt/lists/* \\
                    && wget https://github.com/tsl0922/ttyd/releases/download/1.7.7/ttyd.x86_64 -O /usr/local/bin/ttyd \\
                    && chmod +x /usr/local/bin/ttyd
                COPY . .
                RUN javac *.java 2>/dev/null || true
                EXPOSE 8080
                CMD ["ttyd", "--port", "8080", "--writable", "java", \"""" + mainClass + "\"]\n";
    }

    private String cliPythonDockerfile(Path workspace) {
        String entryFile = findPythonEntryPoint(workspace);
        String installStep;
        if (Files.exists(workspace.resolve("requirements.txt"))) {
            installStep = "RUN pip install --no-cache-dir -r requirements.txt";
        } else if (Files.exists(workspace.resolve("pyproject.toml"))) {
            installStep = "RUN pip install --no-cache-dir .";
        } else {
            installStep = "# No requirements file found";
        }
        return """
                FROM python:3.12-slim
                WORKDIR /app
                LABEL preview=true
                RUN apt-get update && apt-get install -y wget && rm -rf /var/lib/apt/lists/* \\
                    && wget https://github.com/tsl0922/ttyd/releases/download/1.7.7/ttyd.x86_64 -O /usr/local/bin/ttyd \\
                    && chmod +x /usr/local/bin/ttyd
                COPY requirements.txt* pyproject.toml* ./
                """ + installStep + "\n" + """
                COPY . .
                EXPOSE 8080
                CMD ["ttyd", "--port", "8080", "--writable", "python", \"""" + entryFile + "\"]\n";
    }

    private String cliGoDockerfile() {
        return """
                FROM golang:1.22-alpine
                WORKDIR /app
                LABEL preview=true
                RUN apk add --no-cache ttyd
                COPY go.mod go.sum* ./
                RUN go mod download 2>/dev/null || true
                COPY . .
                RUN go build -o app . 2>/dev/null || true
                EXPOSE 8080
                CMD ["sh", "-c", "if [ -f app ]; then ttyd --port 8080 --writable ./app; else ttyd --port 8080 --writable go run .; fi"]
                """;
    }

    private String cliRustDockerfile() {
        return """
                FROM rust:1.77-slim
                RUN apt-get update && apt-get install -y pkg-config libssl-dev wget && rm -rf /var/lib/apt/lists/* \\
                    && wget https://github.com/tsl0922/ttyd/releases/download/1.7.7/ttyd.x86_64 -O /usr/local/bin/ttyd \\
                    && chmod +x /usr/local/bin/ttyd
                WORKDIR /app
                LABEL preview=true
                COPY Cargo.toml Cargo.lock* ./
                RUN mkdir src && echo "fn main() {}" > src/main.rs && cargo build --release 2>/dev/null || true && rm -rf src
                COPY . .
                RUN cargo build --release 2>/dev/null || true
                EXPOSE 8080
                CMD ["sh", "-c", "bin=$(find target/release -maxdepth 1 -type f -executable | head -1) && if [ -n \\"$bin\\" ]; then exec ttyd --port 8080 --writable \\"$bin\\"; else ttyd --port 8080 --writable cargo run --release; fi"]
                """;
    }

    private String cliRubyDockerfile(Path workspace) {
        String entryFile = findRubyEntryPoint(workspace);
        boolean hasBundler = Files.exists(workspace.resolve("Gemfile"));
        String installStep = hasBundler
                ? "RUN apk add --no-cache build-base ttyd && bundle install"
                : "RUN apk add --no-cache ttyd";
        return """
                FROM ruby:3.2-alpine
                WORKDIR /app
                LABEL preview=true
                COPY Gemfile* ./
                """ + installStep + "\n" + """
                COPY . .
                EXPOSE 8080
                CMD ["ttyd", "--port", "8080", "--writable", "ruby", \"""" + entryFile + "\"]\n";
    }

    private String cliPhpDockerfile(Path workspace) {
        boolean hasComposer = Files.exists(workspace.resolve("composer.json"));
        String installStep = hasComposer
                ? "RUN apt-get update && apt-get install -y wget && rm -rf /var/lib/apt/lists/* && \\\\\n" +
                  "    wget https://github.com/tsl0922/ttyd/releases/download/1.7.7/ttyd.x86_64 -O /usr/local/bin/ttyd && \\\\\n" +
                  "    chmod +x /usr/local/bin/ttyd && \\\\\n" +
                  "    curl -sS https://getcomposer.org/installer | php && php composer.phar install --no-dev 2>/dev/null || true"
                : "RUN apt-get update && apt-get install -y wget && rm -rf /var/lib/apt/lists/* && \\\\\n" +
                  "    wget https://github.com/tsl0922/ttyd/releases/download/1.7.7/ttyd.x86_64 -O /usr/local/bin/ttyd && \\\\\n" +
                  "    chmod +x /usr/local/bin/ttyd";
        String entryFile = "index.php";
        for (String candidate : new String[]{"main.php", "app.php", "index.php", "cli.php"}) {
            if (Files.exists(workspace.resolve(candidate))) { entryFile = candidate; break; }
        }
        return """
                FROM php:8.2-cli
                WORKDIR /app
                LABEL preview=true
                COPY . .
                """ + installStep + "\n" +
                "EXPOSE 8080\n" +
                "CMD [\"ttyd\", \"--port\", \"8080\", \"--writable\", \"php\", \"" + entryFile + "\"]\n";
    }

    // ── MCP Dockerfiles ──────────────────────────────────────────────────────

    private String mcpDockerfile(PreviewProjectType type, Path workspace) {
        if (type == PreviewProjectType.PYTHON) return mcpPythonDockerfile(workspace);
        return mcpNodeDockerfile(workspace);
    }

    private String mcpNodeDockerfile(Path workspace) {
        JsonNode pkg = parsePackageJson(workspace);
        String serverCmd = "node index.js";
        if (pkg != null) {
            if (pkg.has("bin")) {
                JsonNode bin = pkg.get("bin");
                if (bin.isTextual()) {
                    serverCmd = "node " + bin.asText();
                } else if (bin.isObject()) {
                    var fields = bin.fields();
                    if (fields.hasNext()) {
                        serverCmd = "node " + fields.next().getValue().asText();
                    }
                }
            } else if (pkg.has("main")) {
                serverCmd = "node " + pkg.get("main").asText();
            }
        }
        return """
                FROM node:20-alpine
                WORKDIR /app
                LABEL preview=true
                RUN npm install -g @modelcontextprotocol/inspector
                COPY package*.json ./
                RUN npm install --legacy-peer-deps
                COPY . .
                EXPOSE 8080
                ENV CLIENT_PORT=8080
                ENV SERVER_PORT=9315
                CMD ["sh", "-c", "npx @modelcontextprotocol/inspector --port 8080 -- """ + serverCmd + "\"]\n";
    }

    private String mcpPythonDockerfile(Path workspace) {
        String entryFile = findPythonEntryPoint(workspace);
        String installStep;
        if (Files.exists(workspace.resolve("requirements.txt"))) {
            installStep = "RUN pip install --no-cache-dir -r requirements.txt";
        } else if (Files.exists(workspace.resolve("pyproject.toml"))) {
            installStep = "RUN pip install --no-cache-dir .";
        } else {
            installStep = "# No requirements file found";
        }
        return """
                FROM python:3.12-slim
                WORKDIR /app
                LABEL preview=true
                RUN apt-get update && apt-get install -y nodejs npm && rm -rf /var/lib/apt/lists/*
                RUN npm install -g @modelcontextprotocol/inspector
                COPY requirements.txt* pyproject.toml* ./
                """ + installStep + "\n" + """
                COPY . .
                EXPOSE 8080
                ENV CLIENT_PORT=8080
                ENV SERVER_PORT=9315
                CMD ["sh", "-c", "npx @modelcontextprotocol/inspector --port 8080 -- python """ + entryFile + "\"]\n";
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

    private boolean requiresPackageJson(PreviewProjectType type) {
        return type == PreviewProjectType.NODE_FRONTEND
            || type == PreviewProjectType.NODE_BACKEND
            || type == PreviewProjectType.FULL_STACK;
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

    private boolean hasJsOrTsFiles(Path workspace) {
        try (Stream<Path> stream = Files.list(workspace)) {
            return stream.anyMatch(p -> {
                String s = p.toString();
                return s.endsWith(".js") || s.endsWith(".ts") || s.endsWith(".mjs");
            });
        } catch (IOException e) {
            return false;
        }
    }

    private boolean hasJavaFiles(Path workspace) {
        try (Stream<Path> stream = Files.list(workspace)) {
            return stream.anyMatch(p -> p.toString().endsWith(".java"));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Check if a Python dependency exists in requirements.txt or pyproject.toml.
     * More reliable than scanning source files (catches package imports in subdirectories).
     */
    private boolean hasDependency(Path workspace, String packageName) {
        Path reqFile = workspace.resolve("requirements.txt");
        if (Files.exists(reqFile) && fileContainsPattern(reqFile, packageName)) {
            return true;
        }
        Path pyproject = workspace.resolve("pyproject.toml");
        return Files.exists(pyproject) && fileContainsPattern(pyproject, packageName);
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

    /**
     * Find the Node.js entry point file. Checks common names in priority order.
     */
    private String findNodeEntryPoint(Path workspace) {
        // Check package.json "main" field first
        JsonNode pkg = parsePackageJson(workspace);
        if (pkg != null && pkg.has("main")) {
            String mainField = pkg.get("main").asText("");
            if (!mainField.isBlank() && Files.exists(workspace.resolve(mainField))) {
                return mainField;
            }
        }

        String[] candidates = {"index.js", "server.js", "app.js", "main.js",
                               "index.mjs", "server.mjs", "app.mjs",
                               "src/index.js", "src/server.js", "src/app.js",
                               "src/index.mjs", "src/server.mjs"};
        for (String name : candidates) {
            if (Files.exists(workspace.resolve(name))) return name;
        }

        // Scan for any .js or .mjs file at workspace root (last resort, pick first alphabetically)
        try (var stream = Files.list(workspace)) {
            return stream
                    .filter(p -> {
                        String fn = p.getFileName().toString();
                        return fn.endsWith(".js") || fn.endsWith(".mjs");
                    })
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .findFirst()
                    .orElse("index.js");
        } catch (Exception e) {
            return "index.js";
        }
    }

    /**
     * Find Node entry point in a specific directory (not workspace root).
     * Used for backend subdirectories that may lack their own package.json.
     */
    private String findNodeEntryPointInDir(Path dir) {
        String[] candidates = {"index.js", "server.js", "app.js", "main.js",
                               "index.mjs", "server.mjs", "app.mjs",
                               "src/index.js", "src/server.js", "src/app.js"};
        for (String name : candidates) {
            if (Files.exists(dir.resolve(name))) return name;
        }
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> {
                        String fn = p.getFileName().toString();
                        return fn.endsWith(".js") || fn.endsWith(".mjs");
                    })
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .findFirst()
                    .orElse("index.js");
        } catch (Exception e) {
            return "index.js";
        }
    }

    /**
     * Find the Python entry point file. Checks common names first,
     * then scans for files containing {@code if __name__}.
     */
    private String findPythonEntryPoint(Path workspace) {
        // Priority order of common entry point names
        String[] candidates = {"main.py", "app.py", "run.py", "server.py", "wsgi.py", "index.py"};
        for (String name : candidates) {
            if (Files.exists(workspace.resolve(name))) return name;
        }
        // Scan for any .py with if __name__ == "__main__"
        String mainFile = findPythonFileWithPattern(workspace, "if __name__");
        if (mainFile != null) return mainFile;
        // Last resort
        return "app.py";
    }
}
