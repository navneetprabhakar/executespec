package com.navneet.executespec.deployment.preview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class DevDockerfileGenerator {

  private static final ObjectMapper mapper = new ObjectMapper();

  /** Captures the layout of a full-stack project with separate frontend/backend subdirectories. */
  record FullStackLayout(
      String frontendDir,
      String backendDir,
      PreviewProjectType frontendType,
      PreviewProjectType backendType) {
    boolean hasFrontend() {
      return frontendDir != null;
    }

    boolean hasBackend() {
      return backendDir != null;
    }
  }

  /** Detect project type from workspace contents. Returns null if unsupported. */
  public PreviewProjectType detect(Path workspace) {
    // 0. Check planner-declared project type in .agent-context/project.json
    Path projectJson = workspace.resolve(".agent-context/project.json");
    if (Files.exists(projectJson)) {
      try {
        JsonNode node = mapper.readTree(projectJson.toFile());
        Optional<PreviewProjectType> fromPlan =
            DeclaredPreviewProjectTypeResolver.resolve(workspace, node);
        if (fromPlan.isPresent()) return fromPlan.get();
      } catch (IllegalArgumentException e) {
        log.warn(
            "[Detect] Invalid planner-declared project type in project.json — falling back to"
                + " detection");
      } catch (IOException e) {
        log.debug("[Detect] Could not read project.json — falling back to file-based detection");
      }
    }

    // 1. Spring Boot — Maven or Gradle (check before package.json in case both exist)
    Path pomXml = workspace.resolve("pom.xml");
    if (Files.exists(pomXml)
        && PreviewFileSignals.fileContainsPattern(pomXml, "spring-boot-starter")) {
      return PreviewProjectType.SPRING_BOOT;
    }
    Path buildGradle = workspace.resolve("build.gradle");
    Path buildGradleKts = workspace.resolve("build.gradle.kts");
    if ((Files.exists(buildGradle)
            && PreviewFileSignals.fileContainsPattern(buildGradle, "spring-boot"))
        || (Files.exists(buildGradleKts)
            && PreviewFileSignals.fileContainsPattern(buildGradleKts, "spring-boot"))) {
      return PreviewProjectType.SPRING_BOOT;
    }

    // 1b. Java CLI
    if (PreviewFileSignals.hasJavaFiles(workspace)) {
      return PreviewProjectType.JAVA;
    }

    // 1c. React Native + Expo (managed workflow). High-signal marker is
    //     app.json at the repo root PLUS `expo` in package.json deps. Check
    //     BEFORE the generic Node branch so RN projects don't misclassify
    //     as NODE_FRONTEND.
    Path appJson = workspace.resolve("app.json");
    Path pkgJsonForExpo = workspace.resolve("package.json");
    if (Files.exists(appJson) && Files.exists(pkgJsonForExpo)) {
      try {
        JsonNode pkgNode = mapper.readTree(pkgJsonForExpo.toFile());
        boolean hasExpoDep = false;
        for (String field : List.of("dependencies", "devDependencies")) {
          JsonNode deps = pkgNode.get(field);
          if (deps != null && deps.has("expo")) {
            hasExpoDep = true;
            break;
          }
        }
        if (hasExpoDep) {
          log.info("[Detect] Expo + React Native project (app.json + expo dep present)");
          return PreviewProjectType.REACT_NATIVE_EXPO;
        }
      } catch (IOException e) {
        log.debug("[Detect] Could not read package.json for Expo check: {}", e.getMessage());
      }
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
            if (PreviewDependencySignals.hasBackendDependency(deps)) hasBackendDep = true;
            if (PreviewDependencySignals.hasFrontendDependency(deps)) hasFrontendDep = true;
          }
        }

        // Single-package full-stack: both backend framework + frontend framework
        // e.g. express + react in one package.json with concurrently
        if (hasBackendDep && hasFrontendDep) {
          log.info(
              "[Detect] Single-package full-stack: backend + frontend deps in same package.json");
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
    if (PreviewFileSignals.hasStaticHtml(workspace)) {
      return PreviewProjectType.STATIC_HTML;
    }

    // 2c. Bare JS/TS files (no package.json, no HTML) — likely a Node script/server
    if (PreviewFileSignals.hasJsOrTsFiles(workspace)) {
      return PreviewProjectType.NODE_BACKEND;
    }

    // 3. Python
    if (Files.exists(workspace.resolve("requirements.txt"))
        || Files.exists(workspace.resolve("pyproject.toml"))
        || !findPythonFiles(workspace).isEmpty()) {
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

  /** Generate Dockerfile content for the given project type. Does NOT write to disk. */
  public String generate(PreviewProjectType type, Path workspace) {
    String dockerfile;

    // Monorepo gets a specialized Dockerfile
    JsonNode pkg = parsePackageJson(workspace);
    if (pkg != null && isMonorepo(pkg, workspace)) {
      dockerfile = monorepoDockerfile(pkg, workspace, type);
    } else if (type == PreviewProjectType.FULL_STACK) {
      // Full-stack gets a multi-service Dockerfile
      FullStackLayout layout = detectFullStack(workspace);
      dockerfile =
          layout != null && layout.hasFrontend() && layout.hasBackend()
              ? fullStackDockerfile(workspace, layout)
              : singlePackageFullStackDockerfile(workspace);
    } else {
      dockerfile =
          switch (type) {
            case NODE_FRONTEND -> nodeFrontendDockerfile(workspace);
            case NODE_BACKEND -> nodeBackendDockerfile(workspace);
            case SPRING_BOOT -> springBootDockerfile(workspace);
            case JAVA -> cliJavaDockerfile(workspace);
            case PYTHON -> pythonDockerfile(workspace);
            case GO -> goDockerfile(workspace);
            case RUST -> rustDockerfile(workspace);
            case FULL_STACK -> singlePackageFullStackDockerfile(workspace);
            case STATIC_HTML -> staticHtmlDockerfile();
            case REACT_NATIVE_EXPO -> reactNativeExpoDockerfile();
          };
    }

    // PREVIEW-ONLY: Use --legacy-peer-deps to avoid peer-dep conflicts during 5-min preview boot.
    // This is a universal fix that catches all variations of 'npm install' or 'npm ci'
    // across all Dockerfile generators (SinglePackage, FullStack, Monorepo, etc).
    //
    // Trade-off (audit 2026-05-12 line-by-line review item N #70): silently skips peer-dep
    // checks, which CAN mask real conflicts. Acceptable here because (a) previews are
    // ephemeral; (b) the customer's actual deploy Dockerfile (see {@link
    // com.navneet.executespec.deployment.DockerfileGenerator}) does NOT apply this flag — so
    // strict peer-dep behaviour is preserved for the deployment pipeline. Surface a single
    // INFO log so operators can see when it kicks in.
    String before = dockerfile;
    dockerfile =
        dockerfile.replaceAll(
            "(?m)^(\\s*RUN\\s+.*npm\\s+(?:install|ci))(\\b(?!.*--legacy-peer-deps))",
            "$1 --legacy-peer-deps");
    if (!dockerfile.equals(before)) {
      log.info(
          "[Preview] injected --legacy-peer-deps into RUN npm install/ci"
              + " (preview-only; production deploy path is unaffected)");
    }

    // Fix CJS/ESM mismatch: if package.json has "type": "module", rename CommonJS config
    // files (.js) to .cjs so Node doesn't choke. Affects postcss.config.js, tailwind.config.js,
    // etc.
    dockerfile = injectEsmConfigFixup(dockerfile, workspace);

    // Vite 5.4+ allowedHosts: patch vite config to allow preview subdomains.
    // Applied centrally so ALL Dockerfile types (node-frontend, full-stack, monorepo) get it.
    // Only injects if the Dockerfile uses a Node base image (vite is irrelevant for Go/Rust/Java).
    if (dockerfile.contains("node:")
        && (type == PreviewProjectType.NODE_FRONTEND || type == PreviewProjectType.FULL_STACK)) {
      dockerfile = injectBeforeCmd(dockerfile, PreviewDockerfileSnippets.viteAllowedHostsPatch());
    }

    return injectNonRootUser(dockerfile, type);
  }

  /**
   * Generate a Cloud Run-compatible Dockerfile from scratch for the given project type. Each type
   * has its own dedicated generator — no regex post-processing.
   *
   * <p>Cloud Run rules enforced by all generators: - EXPOSE 8080, ENV PORT=8080, shell-form CMD (so
   * ${PORT:-8080} expands) - USER directive AFTER all RUN steps, BEFORE CMD - No cross-runtime
   * scripts (e.g. no `node -e` on Java images) - --legacy-peer-deps on all npm install/ci (Node
   * types only)
   */
  public String generateForCloudRun(PreviewProjectType type, Path workspace) {
    return switch (type) {
      case NODE_FRONTEND -> cloudRun_NodeFrontend(workspace);
      case NODE_BACKEND -> cloudRun_NodeBackend(workspace);
      case SPRING_BOOT -> cloudRun_SpringBoot(workspace);
      case PYTHON -> cloudRun_Python(workspace);
      case GO -> cloudRun_Go(workspace);
      case RUST -> cloudRun_Rust(workspace);
      case STATIC_HTML -> cloudRun_StaticHtml();
      case JAVA -> cloudRun_Java(workspace);
      case FULL_STACK -> cloudRun_FullStack(workspace);
      case REACT_NATIVE_EXPO -> reactNativeExpoDockerfile();
    };
  }

  /**
   * Detect missing vite/react deps and return an `npm install -D ...` suffix string. Returns empty
   * string if all deps present.
   */
  private String crInstallMissingViteDeps(Path workspace) {
    StringBuilder missing = new StringBuilder();
    Path pkgJson = workspace.resolve("package.json");
    if (!Files.exists(pkgJson)) return "";
    if (!PreviewFileSignals.fileContainsPattern(pkgJson, "\"vite\"")) missing.append(" vite");
    if (!PreviewFileSignals.fileContainsPattern(pkgJson, "\"@vitejs/plugin-react\""))
      missing.append(" @vitejs/plugin-react");
    if (!PreviewFileSignals.fileContainsPattern(pkgJson, "\"react\"")) missing.append(" react");
    if (!PreviewFileSignals.fileContainsPattern(pkgJson, "\"react-dom\""))
      missing.append(" react-dom");
    if (!PreviewFileSignals.fileContainsPattern(pkgJson, "\"prop-types\""))
      missing.append(" prop-types");
    if (!PreviewFileSignals.fileContainsPattern(pkgJson, "\"lucide-react\""))
      missing.append(" lucide-react");
    if (!PreviewFileSignals.fileContainsPattern(pkgJson, "\"clsx\"")) missing.append(" clsx");
    if (!PreviewFileSignals.fileContainsPattern(pkgJson, "\"tailwind-merge\""))
      missing.append(" tailwind-merge");
    if (missing.isEmpty()) return "";
    return " && npm install -D" + missing + " --legacy-peer-deps";
  }

  /**
   * Detect Python CMD for Cloud Run (shell form, port parameterized). Returns CMD string like "CMD
   * python manage.py runserver 0.0.0.0:${PORT:-8080}".
   */
  private String crDetectPythonCmd(Path workspace, String portExpr) {
    return PreviewPythonSignals.cloudRunCommand(workspace, portExpr);
  }

  private String cloudRun_NodeFrontend(Path workspace) {
    String viteInstall = crInstallMissingViteDeps(workspace);
    boolean hasViteConfig =
        Files.exists(workspace.resolve("vite.config.js"))
            || Files.exists(workspace.resolve("vite.config.ts"));
    JsonNode pkg = parsePackageJson(workspace);
    boolean hasDevScript = pkg != null && pkg.has("scripts") && pkg.get("scripts").has("dev");

    boolean usesVite = hasViteConfig || hasDevScript;
    String cmd;
    if (usesVite) {
      cmd = "CMD npx vite --host 0.0.0.0 --port ${PORT:-8080}";
    } else {
      cmd = "CMD npx serve -s . -l ${PORT:-8080}";
      viteInstall = " && npm install -g serve";
    }

    var sb = new StringBuilder();
    sb.append("FROM node:20-alpine\n");
    sb.append("WORKDIR /app\n");
    sb.append("LABEL preview=true\n");
    sb.append("ENV PORT=8080\n");
    sb.append("COPY package*.json ./\n");
    sb.append("RUN npm install --legacy-peer-deps").append(viteInstall).append("\n");
    sb.append("COPY . .\n");
    if (usesVite) {
      sb.append(PreviewDockerfileSnippets.viteAllowedHostsPatch());
    }
    sb.append("EXPOSE 8080\n");
    sb.append(PreviewDockerfileSnippets.cloudRunNonRootUser(true));
    sb.append(cmd).append("\n");
    return sb.toString();
  }

  private String cloudRun_NodeBackend(Path workspace) {
    JsonNode pkg = parsePackageJson(workspace);
    String cmd;
    if (pkg != null && pkg.has("scripts") && pkg.get("scripts").has("start")) {
      cmd = "CMD PORT=${PORT:-8080} npm start";
    } else if (pkg != null && pkg.has("main")) {
      cmd = "CMD PORT=${PORT:-8080} node " + pkg.get("main").asText("index.js");
    } else {
      String entryFile = findNodeEntryPoint(workspace);
      cmd = "CMD PORT=${PORT:-8080} node " + entryFile;
    }

    var sb = new StringBuilder();
    sb.append("FROM node:20-alpine\n");
    sb.append("WORKDIR /app\n");
    sb.append("LABEL preview=true\n");
    sb.append("ENV HOST=0.0.0.0\n");
    sb.append("ENV PORT=8080\n");
    sb.append("COPY package*.json ./\n");
    sb.append("RUN npm install --legacy-peer-deps\n");
    sb.append("COPY . .\n");
    sb.append("EXPOSE 8080\n");
    sb.append(PreviewDockerfileSnippets.cloudRunNonRootUser(true));
    sb.append(cmd).append("\n");
    return sb.toString();
  }

  private String cloudRun_SpringBoot(Path workspace) {
    return PreviewSpringBootDockerfiles.cloudRun(workspace);
  }

  private String cloudRun_Python(Path workspace) {
    String installStep;
    if (Files.exists(workspace.resolve("requirements.txt"))) {
      installStep = "RUN pip install --no-cache-dir -r requirements.txt";
      // Add uvicorn if FastAPI detected but not in requirements
      if (findPythonFileWithPattern(workspace, "from fastapi", "import fastapi", "FastAPI(") != null
          || hasDependency(workspace, "fastapi")) {
        if (!PreviewFileSignals.fileContainsPattern(
            workspace.resolve("requirements.txt"), "uvicorn")) {
          installStep += " uvicorn";
        }
      }
    } else if (Files.exists(workspace.resolve("pyproject.toml"))) {
      installStep = "RUN pip install --no-cache-dir .";
    } else {
      installStep = "# No requirements file found";
    }

    String cmd = crDetectPythonCmd(workspace, "${PORT:-8080}");

    var sb = new StringBuilder();
    sb.append("FROM python:3.12-slim\n");
    sb.append("WORKDIR /app\n");
    sb.append("LABEL preview=true\n");
    sb.append("ENV PORT=8080\n");
    sb.append("ENV HOST=0.0.0.0\n");
    sb.append("COPY requirements.txt* pyproject.toml* ./\n");
    sb.append(installStep).append("\n");
    sb.append("COPY . .\n");
    sb.append("EXPOSE 8080\n");
    sb.append(PreviewDockerfileSnippets.cloudRunNonRootUser(false));
    sb.append(cmd).append("\n");
    return sb.toString();
  }

  private String cloudRun_Go(Path workspace) {
    return PreviewStandaloneDockerfiles.cloudRunGo();
  }

  private String cloudRun_Rust(Path workspace) {
    return PreviewStandaloneDockerfiles.cloudRunRust();
  }

  private String cloudRun_StaticHtml() {
    return PreviewStandaloneDockerfiles.cloudRunStaticHtml();
  }

  private String cloudRun_Java(Path workspace) {
    return PreviewStandaloneDockerfiles.cloudRunJava(workspace);
  }

  /** Full-stack dispatcher for Cloud Run. Detects sub-type and delegates. */
  private String cloudRun_FullStack(Path workspace) {
    // 1. Check monorepo (npm workspaces)
    JsonNode rootPkg = parsePackageJson(workspace);
    if (rootPkg != null && isMonorepo(rootPkg, workspace)) {
      return cloudRun_Monorepo(rootPkg, workspace);
    }

    // 2. Check directory layout (frontend/ + backend/)
    FullStackLayout layout = detectFullStack(workspace);
    if (layout != null
        && layout.hasFrontend()
        && layout.hasBackend()
        && layout.backendType() != null) {
      if (layout.frontendType() == PreviewProjectType.STATIC_HTML) {
        return cloudRun_FullStack_StaticBackend(workspace, layout);
      }
      return switch (layout.backendType()) {
        case SPRING_BOOT -> cloudRun_FullStack_NodeSpring(workspace, layout);
        case PYTHON -> cloudRun_FullStack_NodePython(workspace, layout);
        case GO -> cloudRun_FullStack_NodeGo(workspace, layout);
        case RUST -> cloudRun_FullStack_NodeRust(workspace, layout);
        case JAVA -> cloudRun_FullStack_NodeJava(workspace, layout);
        default -> cloudRun_FullStack_NodeNode(workspace, layout);
      };
    }

    // 3. Fallback: single-package full-stack (React + Express in same package.json)
    return cloudRun_SinglePkgFullStack(workspace);
  }

  /** Node frontend + Node backend in separate directories — Cloud Run. */
  private String cloudRun_FullStack_NodeNode(Path workspace, FullStackLayout layout) {
    String fe = layout.frontendDir();
    String be = layout.backendDir();
    boolean feIsRoot = ".".equals(fe);

    var sb = new StringBuilder();
    sb.append("FROM node:20-alpine\n");
    sb.append("WORKDIR /app\n");
    sb.append("LABEL preview=true\n");
    sb.append("ENV PORT=8080\n");

    // Install frontend deps
    if (fe != null) {
      if (feIsRoot) {
        sb.append("COPY package*.json ./\n");
        sb.append("RUN npm install --legacy-peer-deps");
      } else {
        sb.append("COPY ").append(fe).append("/package*.json ./").append(fe).append("/\n");
        sb.append("RUN cd ").append(fe).append(" && npm install --legacy-peer-deps");
      }
      Path fePkg =
          feIsRoot
              ? workspace.resolve("package.json")
              : workspace.resolve(fe).resolve("package.json");
      if (Files.exists(fePkg) && !PreviewFileSignals.fileContainsPattern(fePkg, "\"vite\"")) {
        sb.append(" && npm install vite --legacy-peer-deps");
      }
      sb.append("\n");
    }
    // Install backend deps
    if (be != null) {
      Path bePkgPath = workspace.resolve(be).resolve("package.json");
      if (Files.exists(bePkgPath)) {
        sb.append("COPY ").append(be).append("/package*.json ./").append(be).append("/\n");
        sb.append("RUN cd ").append(be).append(" && npm install --legacy-peer-deps\n");
      }
    }

    sb.append("COPY . .\n");

    // Vite patches (runs as root, before USER)
    sb.append(PreviewDockerfileSnippets.viteAllowedHostsPatch());
    sb.append(PreviewDockerfileSnippets.viteProxyPatch(8081));

    sb.append("EXPOSE 8080\n");
    sb.append(PreviewDockerfileSnippets.cloudRunNonRootUser(true));

    // CMD: backend on 8081, frontend (vite) on PORT (8080)
    String feCmd = feIsRoot ? "/app" : "/app/" + fe;
    if (fe != null && be != null) {
      String beStartCmd;
      Path bePkgPath = workspace.resolve(be).resolve("package.json");
      if (Files.exists(bePkgPath)) {
        beStartCmd = "cd /app/" + be + " && PORT=8081 npm start";
      } else {
        String entry = findNodeEntryPointInDir(workspace.resolve(be));
        beStartCmd = "cd /app/" + be + " && PORT=8081 node " + entry;
      }
      sb.append("CMD sh -c \"(")
          .append(beStartCmd)
          .append(" 2>&1 | awk '{print \\\"[backend] \\\" $0; fflush()}') & ")
          .append("(cd ")
          .append(feCmd)
          .append(
              " && npx vite --host 0.0.0.0 --port ${PORT:-8080} 2>&1 | awk '{print \\\"[frontend]"
                  + " \\\" $0; fflush()}') & wait\"\n");
    } else if (fe != null) {
      sb.append("CMD sh -c \"cd ")
          .append(feCmd)
          .append(" && npx vite --host 0.0.0.0 --port ${PORT:-8080}\"\n");
    }
    return sb.toString();
  }

  /** Node frontend + Spring Boot backend — Cloud Run. */
  private String cloudRun_FullStack_NodeSpring(Path workspace, FullStackLayout layout) {
    String fe = layout.frontendDir();
    String be = layout.backendDir();

    var sb = new StringBuilder();
    sb.append("FROM eclipse-temurin:21\n");
    sb.append("WORKDIR /app\n");
    sb.append("LABEL preview=true\n");
    sb.append("ENV PORT=8080\n");
    sb.append("RUN apt-get update && apt-get install -y curl maven && \\\n");
    sb.append("    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \\\n");
    sb.append("    apt-get install -y nodejs && rm -rf /var/lib/apt/lists/*\n");

    // Install backend Maven deps
    if (be != null) {
      sb.append("COPY ").append(be).append("/pom.xml ./").append(be).append("/\n");
      sb.append("RUN cd ").append(be).append(" && mvn dependency:go-offline -q -B || true\n");
    }
    // Install frontend Node deps
    if (fe != null) {
      sb.append("COPY ").append(fe).append("/package*.json ./").append(fe).append("/\n");
      sb.append("RUN cd ").append(fe).append(" && npm install --legacy-peer-deps\n");
    }

    sb.append("COPY . .\n");

    // Vite patches (node is available via apt)
    sb.append(PreviewDockerfileSnippets.viteAllowedHostsPatch());
    sb.append(PreviewDockerfileSnippets.viteProxyPatch(8081));

    sb.append("EXPOSE 8080\n");
    sb.append(PreviewDockerfileSnippets.cloudRunNonRootUser(false));

    if (fe != null && be != null) {
      sb.append("CMD sh -c \"(cd /app/")
          .append(be)
          .append(
              " && SERVER_PORT=8081 mvn spring-boot:run -DskipTests -q 2>&1 | awk '{print"
                  + " \\\"[backend] \\\" $0; fflush()}') & ")
          .append("(cd /app/")
          .append(fe)
          .append(
              " && npx vite --host 0.0.0.0 --port ${PORT:-8080} 2>&1 | awk '{print \\\"[frontend]"
                  + " \\\" $0; fflush()}') & wait\"\n");
    } else if (be != null) {
      sb.append("CMD sh -c \"cd /app/")
          .append(be)
          .append(" && SERVER_PORT=${PORT:-8080} mvn spring-boot:run -DskipTests -q\"\n");
    }
    return sb.toString();
  }

  /** Node frontend + Python backend — Cloud Run. */
  private String cloudRun_FullStack_NodePython(Path workspace, FullStackLayout layout) {
    String fe = layout.frontendDir();
    String be = layout.backendDir();

    var sb = new StringBuilder();
    sb.append("FROM python:3.12-slim\n");
    sb.append("WORKDIR /app\n");
    sb.append("LABEL preview=true\n");
    sb.append("ENV PORT=8080\n");
    sb.append("RUN apt-get update && apt-get install -y curl && \\\n");
    sb.append("    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \\\n");
    sb.append("    apt-get install -y nodejs && rm -rf /var/lib/apt/lists/*\n");

    // Install backend Python deps
    if (be != null) {
      Path beDir = workspace.resolve(be);
      sb.append("COPY ")
          .append(be)
          .append("/requirements.txt* ")
          .append(be)
          .append("/pyproject.toml* ./")
          .append(be)
          .append("/\n");
      if (Files.exists(beDir.resolve("requirements.txt"))) {
        sb.append("RUN cd ")
            .append(be)
            .append(" && pip install --no-cache-dir -r requirements.txt\n");
      } else if (Files.exists(beDir.resolve("pyproject.toml"))) {
        sb.append("RUN cd ").append(be).append(" && pip install --no-cache-dir .\n");
      }
    }
    // Install frontend Node deps
    if (fe != null) {
      sb.append("COPY ").append(fe).append("/package*.json ./").append(fe).append("/\n");
      sb.append("RUN cd ").append(fe).append(" && npm install --legacy-peer-deps\n");
    }

    sb.append("COPY . .\n");

    // Vite patches (node is available via apt)
    sb.append(PreviewDockerfileSnippets.viteAllowedHostsPatch());
    sb.append(PreviewDockerfileSnippets.viteProxyPatch(8081));

    sb.append("EXPOSE 8080\n");

    // Detect Python backend CMD
    String pythonCmd = "python app.py";
    if (be != null) {
      Path beDir = workspace.resolve(be);
      pythonCmd = crDetectPythonCmd(beDir, "8081").replace("CMD ", "");
    }

    sb.append(PreviewDockerfileSnippets.cloudRunNonRootUser(false));

    if (fe != null && be != null) {
      sb.append("CMD sh -c \"(cd /app/")
          .append(be)
          .append(" && ")
          .append(pythonCmd)
          .append(" 2>&1 | awk '{print \\\"[backend] \\\" $0; fflush()}') & ")
          .append("(cd /app/")
          .append(fe)
          .append(
              " && npx vite --host 0.0.0.0 --port ${PORT:-8080} 2>&1 | awk '{print \\\"[frontend]"
                  + " \\\" $0; fflush()}') & wait\"\n");
    } else if (be != null) {
      sb.append("CMD sh -c \"cd /app/").append(be).append(" && ").append(pythonCmd).append("\"\n");
    }
    return sb.toString();
  }

  /** Node/Vite frontend + Go backend — Cloud Run. */
  private String cloudRun_FullStack_NodeGo(Path workspace, FullStackLayout layout) {
    String fe = layout.frontendDir();
    String be = layout.backendDir();

    var sb = new StringBuilder();
    sb.append("FROM golang:1.22-alpine\n");
    sb.append("WORKDIR /app\n");
    sb.append("LABEL preview=true\n");
    sb.append("ENV PORT=8080\n");
    sb.append("RUN apk add --no-cache nodejs npm\n");

    // Install backend Go deps
    if (be != null) {
      sb.append("COPY ")
          .append(be)
          .append("/go.mod ")
          .append(be)
          .append("/go.sum* ./")
          .append(be)
          .append("/\n");
      sb.append("RUN cd ").append(be).append(" && go mod download 2>/dev/null || true\n");
    }
    // Install frontend Node deps
    if (fe != null) {
      sb.append("COPY ").append(fe).append("/package*.json ./").append(fe).append("/\n");
      sb.append("RUN cd ").append(fe).append(" && npm install --legacy-peer-deps\n");
    }

    sb.append("COPY . .\n");
    sb.append(PreviewDockerfileSnippets.viteAllowedHostsPatch());
    sb.append(PreviewDockerfileSnippets.viteProxyPatch(8081));
    sb.append("EXPOSE 8080\n");
    sb.append(PreviewDockerfileSnippets.cloudRunNonRootUser(true));

    if (fe != null && be != null) {
      sb.append("CMD sh -c \"(cd /app/")
          .append(be)
          .append(" && PORT=8081 go run . 2>&1 | awk '{print \\\"[backend] \\\" $0; fflush()}') & ")
          .append("(cd /app/")
          .append(fe)
          .append(
              " && npx vite --host 0.0.0.0 --port ${PORT:-8080} 2>&1 | awk '{print \\\"[frontend]"
                  + " \\\" $0; fflush()}') & wait\"\n");
    }
    return sb.toString();
  }

  /** Node/Vite frontend + Rust backend — Cloud Run. */
  private String cloudRun_FullStack_NodeRust(Path workspace, FullStackLayout layout) {
    String fe = layout.frontendDir();
    String be = layout.backendDir();

    var sb = new StringBuilder();
    sb.append("FROM rust:1.77-slim\n");
    sb.append("WORKDIR /app\n");
    sb.append("LABEL preview=true\n");
    sb.append("ENV PORT=8080\n");
    sb.append("RUN apt-get update && apt-get install -y curl pkg-config libssl-dev && \\\n");
    sb.append("    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \\\n");
    sb.append("    apt-get install -y nodejs && rm -rf /var/lib/apt/lists/*\n");

    // Pre-build backend deps (cargo fetch)
    if (be != null) {
      sb.append("COPY ")
          .append(be)
          .append("/Cargo.toml ")
          .append(be)
          .append("/Cargo.lock* ./")
          .append(be)
          .append("/\n");
      sb.append("RUN cd ")
          .append(be)
          .append(
              " && mkdir src && echo \"fn main() {}\" > src/main.rs && cargo build --release"
                  + " 2>/dev/null || true && rm -rf src\n");
    }
    // Install frontend Node deps
    if (fe != null) {
      sb.append("COPY ").append(fe).append("/package*.json ./").append(fe).append("/\n");
      sb.append("RUN cd ").append(fe).append(" && npm install --legacy-peer-deps\n");
    }

    sb.append("COPY . .\n");

    // Build backend
    if (be != null) {
      sb.append("RUN cd ").append(be).append(" && cargo build --release 2>/dev/null || true\n");
    }

    sb.append(PreviewDockerfileSnippets.viteAllowedHostsPatch());
    sb.append(PreviewDockerfileSnippets.viteProxyPatch(8081));
    sb.append("EXPOSE 8080\n");
    sb.append(PreviewDockerfileSnippets.cloudRunNonRootUser(false));

    if (fe != null && be != null) {
      sb.append("CMD sh -c \"(cd /app/")
          .append(be)
          .append(
              " && PORT=8081 bin=$(find target/release -maxdepth 1 -type f -executable | head -1)"
                  + " && if [ -n \\\"$bin\\\" ]; then exec \\\"$bin\\\"; else cargo run --release;"
                  + " fi")
          .append(" 2>&1 | awk '{print \\\"[backend] \\\" $0; fflush()}') & ")
          .append("(cd /app/")
          .append(fe)
          .append(
              " && npx vite --host 0.0.0.0 --port ${PORT:-8080} 2>&1 | awk '{print \\\"[frontend]"
                  + " \\\" $0; fflush()}') & wait\"\n");
    }
    return sb.toString();
  }

  /** Node/Vite frontend + Java (non-Spring) backend — Cloud Run. */
  private String cloudRun_FullStack_NodeJava(Path workspace, FullStackLayout layout) {
    String fe = layout.frontendDir();
    String be = layout.backendDir();

    // Detect Java main class
    String mainClass = "Main";
    if (be != null) {
      Path beDir = workspace.resolve(be);
      try (Stream<Path> stream = Files.walk(beDir, 3)) {
        Path mainFile =
            stream
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> PreviewFileSignals.fileContainsPattern(p, "public static void main"))
                .findFirst()
                .orElse(null);
        if (mainFile != null) {
          String filename = mainFile.getFileName().toString();
          mainClass = filename.substring(0, filename.length() - 5);
        }
      } catch (IOException ignored) {
      }
    }

    var sb = new StringBuilder();
    sb.append("FROM eclipse-temurin:21\n");
    sb.append("WORKDIR /app\n");
    sb.append("LABEL preview=true\n");
    sb.append("ENV PORT=8080\n");
    sb.append("RUN apt-get update && apt-get install -y curl && \\\n");
    sb.append("    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \\\n");
    sb.append("    apt-get install -y nodejs && rm -rf /var/lib/apt/lists/*\n");

    // Install frontend Node deps
    if (fe != null) {
      sb.append("COPY ").append(fe).append("/package*.json ./").append(fe).append("/\n");
      sb.append("RUN cd ").append(fe).append(" && npm install --legacy-peer-deps\n");
    }

    sb.append("COPY . .\n");

    // Compile Java backend
    if (be != null) {
      sb.append("RUN cd ").append(be).append(" && javac *.java 2>/dev/null || true\n");
    }

    sb.append(PreviewDockerfileSnippets.viteAllowedHostsPatch());
    sb.append(PreviewDockerfileSnippets.viteProxyPatch(8081));
    sb.append("EXPOSE 8080\n");
    sb.append(PreviewDockerfileSnippets.cloudRunNonRootUser(false));

    if (fe != null && be != null) {
      sb.append("CMD sh -c \"(cd /app/")
          .append(be)
          .append(" && PORT=8081 java ")
          .append(mainClass)
          .append(" 2>&1 | awk '{print \\\"[backend] \\\" $0; fflush()}') & ")
          .append("(cd /app/")
          .append(fe)
          .append(
              " && npx vite --host 0.0.0.0 --port ${PORT:-8080} 2>&1 | awk '{print \\\"[frontend]"
                  + " \\\" $0; fflush()}') & wait\"\n");
    }
    return sb.toString();
  }

  /**
   * Static HTML frontend + any backend — Cloud Run. Uses nginx for static files + reverse proxy.
   */
  private String cloudRun_FullStack_StaticBackend(Path workspace, FullStackLayout layout) {
    String fe = layout.frontendDir();
    String be = layout.backendDir();
    boolean feIsRoot = ".".equals(fe);
    PreviewProjectType beType = layout.backendType();

    // Determine base image and whether it's Alpine-based
    boolean alpine = (beType == PreviewProjectType.GO || beType == PreviewProjectType.NODE_BACKEND);

    var sb = new StringBuilder();

    // Base image per backend type
    switch (beType) {
      case GO -> sb.append("FROM golang:1.22-alpine\n");
      case NODE_BACKEND -> sb.append("FROM node:20-alpine\n");
      case SPRING_BOOT -> sb.append("FROM eclipse-temurin:21\n");
      case PYTHON -> sb.append("FROM python:3.12-slim\n");
      case RUST -> sb.append("FROM rust:1.77-slim\n");
      case JAVA -> sb.append("FROM eclipse-temurin:21\n");
      default -> sb.append("FROM node:20-alpine\n");
    }

    sb.append("WORKDIR /app\n");
    sb.append("LABEL preview=true\n");
    sb.append("ENV PORT=8080\n");

    // Install nginx
    if (alpine) {
      sb.append("RUN apk add --no-cache nginx\n");
    } else {
      sb.append("RUN apt-get update && apt-get install -y nginx && rm -rf /var/lib/apt/lists/*\n");
    }

    // Install backend-specific deps
    if (be != null) {
      Path beDir = workspace.resolve(be);
      switch (beType) {
        case GO -> {
          sb.append("COPY ")
              .append(be)
              .append("/go.mod ")
              .append(be)
              .append("/go.sum* ./")
              .append(be)
              .append("/\n");
          sb.append("RUN cd ").append(be).append(" && go mod download 2>/dev/null || true\n");
        }
        case NODE_BACKEND -> {
          sb.append("COPY ").append(be).append("/package*.json ./").append(be).append("/\n");
          sb.append("RUN cd ").append(be).append(" && npm install\n");
        }
        case SPRING_BOOT -> {
          sb.append(
              "RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*\n");
          sb.append("COPY ").append(be).append("/pom.xml ./").append(be).append("/\n");
          sb.append("RUN cd ").append(be).append(" && mvn dependency:go-offline -q -B || true\n");
        }
        case PYTHON -> {
          if (Files.exists(beDir.resolve("requirements.txt"))) {
            sb.append("COPY ").append(be).append("/requirements.txt ./").append(be).append("/\n");
            sb.append("RUN cd ")
                .append(be)
                .append(" && pip install --no-cache-dir -r requirements.txt\n");
          } else if (Files.exists(beDir.resolve("pyproject.toml"))) {
            sb.append("COPY ").append(be).append("/pyproject.toml ./").append(be).append("/\n");
            sb.append("RUN cd ").append(be).append(" && pip install --no-cache-dir .\n");
          }
        }
        case RUST -> {
          sb.append(
              "RUN apt-get update && apt-get install -y pkg-config libssl-dev && rm -rf"
                  + " /var/lib/apt/lists/*\n");
          sb.append("COPY ")
              .append(be)
              .append("/Cargo.toml ")
              .append(be)
              .append("/Cargo.lock* ./")
              .append(be)
              .append("/\n");
          sb.append("RUN cd ")
              .append(be)
              .append(
                  " && mkdir src && echo \"fn main() {}\" > src/main.rs && cargo build --release"
                      + " 2>/dev/null || true && rm -rf src\n");
        }
        case JAVA -> {
          // Plain Java — just copy & compile later
        }
        default -> {}
      }
    }

    sb.append("COPY . .\n");

    // Build steps (Rust cargo build, Java javac, Go build, Spring Boot compile)
    if (be != null) {
      switch (beType) {
        case RUST ->
            sb.append("RUN cd ")
                .append(be)
                .append(" && cargo build --release 2>/dev/null || true\n");
        case JAVA ->
            sb.append("RUN cd ").append(be).append(" && javac *.java 2>/dev/null || true\n");
        case GO ->
            sb.append("RUN cd ")
                .append(be)
                .append(" && go build -o server . 2>/dev/null || true\n");
        default -> {}
      }
    }

    // nginx config: serve static files + proxy /api to backend
    String staticSrc = feIsRoot ? "/app" : "/app/" + fe;
    sb.append("RUN mkdir -p /usr/share/nginx/html && cp -r ")
        .append(staticSrc)
        .append("/*.html ")
        .append(staticSrc)
        .append("/*.css* ")
        .append(staticSrc)
        .append("/*.js* /usr/share/nginx/html/ 2>/dev/null || true\n");
    sb.append("RUN echo 'server { listen 8080; root /usr/share/nginx/html; index index.html; ")
        .append("location / { try_files \\$uri \\$uri/ /index.html =404; } ")
        .append(
            "location /api { proxy_pass http://127.0.0.1:8081; } }' >"
                + " /etc/nginx/conf.d/default.conf\n");

    // Fix nginx permissions
    if (alpine) {
      sb.append(
          "RUN mkdir -p /var/cache/nginx /var/log/nginx /run/nginx && chown -R nginx:nginx"
              + " /var/cache/nginx /var/log/nginx /etc/nginx/conf.d /run/nginx 2>/dev/null || true"
              + " && touch /var/run/nginx.pid && chown nginx:nginx /var/run/nginx.pid\n");
    } else {
      sb.append(
          "RUN chown -R www-data:www-data /var/log/nginx /etc/nginx/conf.d 2>/dev/null || true\n");
    }

    sb.append("EXPOSE 8080\n");

    // Detect backend start command
    String backendCmd = crDetectStaticBackendCmd(workspace, layout);

    sb.append("CMD sh -c \"(")
        .append(backendCmd)
        .append(" 2>&1 | awk '{print \\\"[backend] \\\" $0; fflush()}') & ")
        .append("nginx -g 'daemon off;'\"\n");

    return sb.toString();
  }

  /** Detect the backend startup command for Static HTML + backend combos. */
  private String crDetectStaticBackendCmd(Path workspace, FullStackLayout layout) {
    String be = layout.backendDir();
    Path beDir = be != null ? workspace.resolve(be) : workspace;
    String prefix = be != null ? "cd /app/" + be + " && " : "";

    return switch (layout.backendType()) {
      case NODE_BACKEND -> {
        String entry = findNodeEntryPointInDir(beDir);
        yield prefix + "PORT=8081 node " + entry;
      }
      case SPRING_BOOT -> prefix + "SERVER_PORT=8081 mvn spring-boot:run -DskipTests -q";
      case PYTHON -> {
        String cmd = crDetectPythonCmd(beDir, "8081").replace("CMD ", "");
        yield prefix + cmd;
      }
      case GO -> prefix + "PORT=8081 go run .";
      case RUST ->
          prefix
              + "PORT=8081 bin=$(find target/release -maxdepth 1 -type f -executable | head -1) &&"
              + " if [ -n \\\"$bin\\\" ]; then exec \\\"$bin\\\"; else cargo run --release; fi";
      case JAVA -> {
        String mainClass = "Main";
        try (Stream<Path> stream = Files.walk(beDir, 3)) {
          Path mainFile =
              stream
                  .filter(p -> p.toString().endsWith(".java"))
                  .filter(p -> PreviewFileSignals.fileContainsPattern(p, "public static void main"))
                  .findFirst()
                  .orElse(null);
          if (mainFile != null) {
            String filename = mainFile.getFileName().toString();
            mainClass = filename.substring(0, filename.length() - 5);
          }
        } catch (IOException ignored) {
        }
        yield prefix + "PORT=8081 java " + mainClass;
      }
      default -> prefix + "PORT=8081 node server.js";
    };
  }

  /**
   * Single-package full-stack: React + Express in same package.json — Cloud Run. Vite hardcoded to
   * 8080 (Cloud Run's exposed port). Backend gets PORT=3001 so Express reads 3001 and doesn't
   * compete with Vite for 8080.
   */
  private String cloudRun_SinglePkgFullStack(Path workspace) {
    return PreviewSinglePackageFullStackDockerfiles.cloudRun(workspace);
  }

  /** Monorepo (npm workspaces) — Cloud Run. */
  private String cloudRun_Monorepo(JsonNode rootPkg, Path workspace) {
    var sb = new StringBuilder();
    sb.append("FROM node:20-alpine\n");
    sb.append("WORKDIR /app\n");
    sb.append("LABEL preview=true\n");
    sb.append("ENV PORT=8080\n");

    // Copy root package files
    sb.append("COPY package.json package-lock.json* ./\n");

    // Copy each workspace's package.json
    for (JsonNode ws : rootPkg.get("workspaces")) {
      String wsName = ws.asText();
      Path wsDir = workspace.resolve(wsName);
      if (Files.exists(wsDir.resolve("package.json"))) {
        sb.append("COPY ").append(wsName).append("/package.json ./").append(wsName).append("/\n");
      }
    }

    sb.append("RUN npm install --legacy-peer-deps\n");
    sb.append("COPY . .\n");

    // Detect frontend and backend workspaces
    String frontendWs = null;
    String backendWs = null;
    for (JsonNode ws : rootPkg.get("workspaces")) {
      String wsName = ws.asText();
      Path wsDir = workspace.resolve(wsName);
      Path wsPkg = wsDir.resolve("package.json");
      if (!Files.exists(wsPkg)) continue;

      try {
        JsonNode pkg = mapper.readTree(wsPkg.toFile());
        JsonNode deps = pkg.get("dependencies");
        JsonNode devDeps = pkg.get("devDependencies");
        boolean isFrontend = PreviewDependencySignals.hasMonorepoFrontendSignal(deps, devDeps);
        boolean isBackend = PreviewDependencySignals.hasBackendDependency(deps);
        if (isFrontend && frontendWs == null) frontendWs = wsName;
        if (isBackend && backendWs == null) backendWs = wsName;
      } catch (IOException e) {
        log.debug("Failed to parse workspace package.json: {}", e.getMessage());
      }
    }

    // Vite patches
    sb.append(PreviewDockerfileSnippets.viteAllowedHostsPatch());
    if (backendWs != null) {
      sb.append(PreviewDockerfileSnippets.viteProxyPatch(8081));
    }

    sb.append("EXPOSE 8080\n");
    sb.append(PreviewDockerfileSnippets.cloudRunNonRootUser(true));

    if (frontendWs != null && backendWs != null) {
      sb.append("CMD sh -c \"(PORT=8081 npm run dev --prefix ")
          .append(backendWs)
          .append(" 2>&1 | awk '{print \\\"[backend] \\\" $0; fflush()}') & ")
          .append("(npx --prefix ")
          .append(frontendWs)
          .append(
              " vite --host 0.0.0.0 --port ${PORT:-8080} 2>&1 | awk '{print \\\"[frontend] \\\" $0;"
                  + " fflush()}') & wait\"\n");
    } else if (frontendWs != null) {
      sb.append("CMD sh -c \"npx --prefix ")
          .append(frontendWs)
          .append(" vite --host 0.0.0.0 --port ${PORT:-8080}\"\n");
    } else if (backendWs != null) {
      sb.append("CMD sh -c \"PORT=${PORT:-8080} npm run dev --prefix ")
          .append(backendWs)
          .append("\"\n");
    } else {
      sb.append("CMD sh -c \"PORT=${PORT:-8080} npm run dev\"\n");
    }
    return sb.toString();
  }

  /** Returns .dockerignore content. */
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

  /**
   * Injects a non-root USER directive before the final CMD/ENTRYPOINT in the Dockerfile. Detects
   * Alpine vs Debian base images for correct user-creation syntax. STATIC_HTML (nginx) uses the
   * built-in nginx user.
   */
  /**
   * If package.json declares "type": "module", CommonJS config files (.js extension using
   * module.exports) will fail at runtime. Inject a RUN step after COPY that renames known config
   * files (postcss, tailwind, etc.) from .js to .cjs.
   */
  private String injectEsmConfigFixup(String dockerfile, Path workspace) {
    // Only relevant for Node.js projects
    Path pkgJson = workspace.resolve("package.json");
    if (!Files.exists(pkgJson)) return dockerfile;
    try {
      String pkgContent = Files.readString(pkgJson);
      if (!pkgContent.contains("\"type\"") || !pkgContent.contains("\"module\"")) return dockerfile;
      // Verify it's actually "type": "module" (not just a field containing the word)
      JsonNode pkg = mapper.readTree(pkgContent);
      if (!"module".equals(pkg.path("type").asText(""))) return dockerfile;
    } catch (Exception e) {
      return dockerfile;
    }

    // Known config files that commonly use module.exports (CJS syntax)
    String fixupCmd =
        "RUN for f in postcss.config.js tailwind.config.js babel.config.js "
            + "jest.config.js prettier.config.js commitlint.config.js lint-staged.config.js; do "
            + "if [ -f \"$f\" ] && grep -q 'module\\.exports' \"$f\" 2>/dev/null; then "
            + "mv \"$f\" \"${f%.js}.cjs\"; fi; done";

    // Insert after the last COPY . . line
    int lastCopy = dockerfile.lastIndexOf("COPY . .");
    if (lastCopy < 0) return dockerfile;
    int insertPoint = dockerfile.indexOf('\n', lastCopy);
    if (insertPoint < 0) return dockerfile;
    return dockerfile.substring(0, insertPoint + 1)
        + fixupCmd
        + "\n"
        + dockerfile.substring(insertPoint + 1);
  }

  private String injectNonRootUser(String dockerfile, PreviewProjectType type) {
    if (dockerfile == null || dockerfile.isBlank()) return dockerfile;
    // Already has a USER directive — skip
    if (dockerfile.contains("\nUSER ")) return dockerfile;

    // STATIC_HTML uses nginx image which has its own user
    if (type == PreviewProjectType.STATIC_HTML) {
      return injectBeforeCmd(dockerfile, "USER nginx\n");
    }

    // Detect Alpine vs Debian/Ubuntu base from FROM line
    boolean isAlpine =
        dockerfile.lines().filter(l -> l.startsWith("FROM ")).anyMatch(l -> l.contains("-alpine"));

    String userBlock;
    if (isAlpine) {
      // -h creates + assigns a home dir on busybox adduser; -D skips password.
      userBlock =
          "RUN addgroup -S app && adduser -S -D -H -h /home/app -G app app "
              + "&& mkdir -p /home/app && chown -R app:app /app /home/app\n"
              + "ENV HOME=/home/app\n"
              + "USER app\n";
    } else {
      // -m creates the home dir; explicit HOME env covers cases where the
      // runtime (Maven, npm, pip) reads HOME rather than /etc/passwd. Without
      // this, Maven fails with "Could not create local repository at /home/app/.m2".
      userBlock =
          "RUN groupadd -r app && useradd -r -m -g app -s /bin/sh app "
              + "&& chown -R app:app /app /home/app\n"
              + "ENV HOME=/home/app\n"
              + "USER app\n";
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

  private String nodeFrontendDockerfile(Path workspace) {
    String cmd;
    boolean hasViteConfig =
        Files.exists(workspace.resolve("vite.config.js"))
            || Files.exists(workspace.resolve("vite.config.ts"));
    JsonNode pkg = parsePackageJson(workspace);
    boolean hasDevScript = pkg != null && pkg.has("scripts") && pkg.get("scripts").has("dev");

    if (hasViteConfig || hasDevScript) {
      // Always use vite with explicit --host and --port to ensure container accessibility
      cmd = "CMD [\"npx\", \"vite\", \"--host\", \"0.0.0.0\", \"--port\", \"5173\"]";
    } else {
      // `serve` defaults to localhost; `-l tcp://0.0.0.0:5173` binds to all
      // interfaces so Docker's port-forward can reach it from the host.
      cmd =
          """
          RUN npm install -g serve
          CMD ["npx", "serve", "-s", ".", "-l", "tcp://0.0.0.0:5173"]""";
    }

    // Ensure vite and common React dependencies are installed at build time
    String viteInstall = "";
    if (cmd.contains("vite")) {
      StringBuilder missing = new StringBuilder();
      if (!PreviewFileSignals.fileContainsPattern(workspace.resolve("package.json"), "\"vite\""))
        missing.append(" vite");
      if (!PreviewFileSignals.fileContainsPattern(
          workspace.resolve("package.json"), "\"@vitejs/plugin-react\""))
        missing.append(" @vitejs/plugin-react");
      if (!PreviewFileSignals.fileContainsPattern(workspace.resolve("package.json"), "\"react\""))
        missing.append(" react");
      if (!PreviewFileSignals.fileContainsPattern(
          workspace.resolve("package.json"), "\"react-dom\"")) missing.append(" react-dom");
      if (!PreviewFileSignals.fileContainsPattern(
          workspace.resolve("package.json"), "\"prop-types\"")) missing.append(" prop-types");
      if (!PreviewFileSignals.fileContainsPattern(
          workspace.resolve("package.json"), "\"lucide-react\"")) missing.append(" lucide-react");
      if (!PreviewFileSignals.fileContainsPattern(workspace.resolve("package.json"), "\"clsx\""))
        missing.append(" clsx");
      if (!PreviewFileSignals.fileContainsPattern(
          workspace.resolve("package.json"), "\"tailwind-merge\""))
        missing.append(" tailwind-merge");

      if (missing.length() > 0) {
        viteInstall = " && npm install -D" + missing.toString() + " --legacy-peer-deps";
      }
    }

    return """
           FROM node:20-alpine
           WORKDIR /app
           LABEL preview=true
           COPY package*.json ./
           RUN npm install --legacy-peer-deps"""
        + viteInstall
        + "\n"
        + "COPY . .\n"
        + "EXPOSE 5173\n"
        + cmd.stripLeading()
        + "\n";
  }

  private String nodeBackendDockerfile(Path workspace) {
    String cmd;
    JsonNode pkg = parsePackageJson(workspace);

    if (pkg != null && pkg.has("scripts") && pkg.get("scripts").has("start")) {
      cmd =
          """
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
           """
        + cmd.stripLeading()
        + "\n";
  }

  /**
   * Single-package full-stack: both frontend (React/Vue/etc) and backend (Express/etc) in the same
   * package.json, typically run via "concurrently" or similar. Uses "npm run dev" if available,
   * otherwise starts backend + vite in parallel.
   */
  /**
   * Single-package full-stack: both frontend (React/Vue/etc) and backend (Express/etc) in the same
   * package.json, typically run via "concurrently" or similar. Uses "npm run dev:full" if
   * available, falling back to "dev" or "start".
   */
  private String singlePackageFullStackDockerfile(Path workspace) {
    return PreviewSinglePackageFullStackDockerfiles.dockerfile(workspace);
  }

  private String springBootDockerfile(Path workspace) {
    return PreviewSpringBootDockerfiles.dockerfile(workspace);
  }

  private String pythonDockerfile(Path workspace) {
    PreviewPythonSignals.DockerCommand pythonCommand =
        PreviewPythonSignals.dockerCommand(workspace);
    String cmd = pythonCommand.command();
    String envLine = pythonCommand.envLine();

    // Safety net: set ALL common host/port env vars for Python web frameworks.
    // Different apps read different env var names:
    //   flask run CLI     → FLASK_RUN_HOST, FLASK_RUN_PORT
    //   app.run()         → FLASK_HOST, FLASK_PORT (custom, but common pattern)
    //   uvicorn           → HOST, PORT
    //   generic           → HOST, PORT
    envLine +=
        "ENV FLASK_RUN_HOST=0.0.0.0 FLASK_RUN_PORT=8000 "
            + "FLASK_HOST=0.0.0.0 FLASK_PORT=8000 "
            + "HOST=0.0.0.0 PORT=8000\n";

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
           """
        + installStep
        + "\n"
        + "COPY . .\n"
        + "EXPOSE 8000\n"
        + envLine
        + cmd.stripLeading()
        + "\n";
  }

  // Full-stack support (directory-based)

  /**
   * Scan subdirectories for frontend/backend projects. Handles cases where frontend/ and backend/
   * exist as sibling directories without npm workspaces (e.g., React + Spring Boot, React +
   * Express, React + Python).
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
        if (frontendDir == null && PreviewDependencySignals.isFrontendDirName(name)) {
          PreviewProjectType detected = detectSingleDir(dir);
          if (detected == PreviewProjectType.NODE_FRONTEND) {
            frontendDir = dir.getFileName().toString();
            frontendType = detected;
          } else if (detected == null && PreviewFileSignals.hasStaticHtml(dir)) {
            frontendDir = dir.getFileName().toString();
            frontendType = PreviewProjectType.STATIC_HTML;
          }
        }

        // Check backend directories
        if (backendDir == null && PreviewDependencySignals.isBackendDirName(name)) {
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
            if (PreviewDependencySignals.hasFrontendDependency(deps)) rootIsFrontend = true;
            if (rootIsFrontend) break;
          }
          if (rootIsFrontend) {
            frontendDir = ".";
            frontendType = PreviewProjectType.NODE_FRONTEND;
            log.info("[Detect] Root is frontend (vite/react), backend in {}/", backendDir);
          }
        } catch (IOException ignored) {
        }
      }
      // Static HTML fallback: root has .html files but no package.json
      if (frontendDir == null
          && !Files.exists(workspace.resolve("package.json"))
          && PreviewFileSignals.hasStaticHtml(workspace)) {
        frontendDir = ".";
        frontendType = PreviewProjectType.STATIC_HTML;
        log.info("[Detect] Root is static HTML frontend, backend in {}/", backendDir);
      }
    }

    if (frontendDir == null && backendDir == null) return null;
    return new FullStackLayout(frontendDir, backendDir, frontendType, backendType);
  }

  /** Detect project type for a single subdirectory. */
  private PreviewProjectType detectSingleDir(Path dir) {
    // Spring Boot
    Path pomXml = dir.resolve("pom.xml");
    if (Files.exists(pomXml)
        && PreviewFileSignals.fileContainsPattern(pomXml, "spring-boot-starter")) {
      return PreviewProjectType.SPRING_BOOT;
    }

    // Node — check for own package.json with backend deps
    if (Files.exists(dir.resolve("package.json"))) {
      JsonNode pkg = null;
      try {
        pkg = mapper.readTree(dir.resolve("package.json").toFile());
      } catch (IOException ignored) {
      }

      if (pkg != null) {
        for (String field : List.of("dependencies", "devDependencies")) {
          JsonNode deps = pkg.get(field);
          if (PreviewDependencySignals.hasBackendDependency(deps)) {
            return PreviewProjectType.NODE_BACKEND;
          }
        }
      }
      return PreviewProjectType.NODE_FRONTEND;
    }

    // Node — no package.json but has JS/TS files (backend code lives in subdir,
    // deps are in root package.json). Scan for server-like files.
    String dirName = dir.getFileName().toString().toLowerCase();
    if (PreviewDependencySignals.isBackendDirName(dirName)
        && PreviewFileSignals.hasJsOrTsFiles(dir)) {
      // Check for server indicators: express patterns, http.createServer, app.listen
      try (Stream<Path> files = Files.walk(dir, 2)) {
        boolean hasServerCode =
            files
                .filter(f -> f.toString().endsWith(".js") || f.toString().endsWith(".ts"))
                .anyMatch(
                    f ->
                        PreviewFileSignals.fileContainsPattern(
                            f, "express\\(|createServer|app\\.listen|fastify|new Hono|Koa\\("));
        if (hasServerCode) return PreviewProjectType.NODE_BACKEND;
      } catch (IOException ignored) {
      }
    }

    // Python
    if (Files.exists(dir.resolve("requirements.txt"))
        || Files.exists(dir.resolve("pyproject.toml"))) {
      return PreviewProjectType.PYTHON;
    }

    // Go
    if (Files.exists(dir.resolve("go.mod"))) {
      return PreviewProjectType.GO;
    }

    // Rust
    if (Files.exists(dir.resolve("Cargo.toml"))) {
      return PreviewProjectType.RUST;
    }

    // Java (non-Spring) — pom.xml without spring-boot-starter, or build.gradle
    if (Files.exists(dir.resolve("pom.xml"))
        || Files.exists(dir.resolve("build.gradle"))
        || Files.exists(dir.resolve("build.gradle.kts"))) {
      return PreviewProjectType.JAVA;
    }

    // Java (plain) — .java files without any build tool (e.g., javac-only projects)
    if (PreviewFileSignals.hasJavaFiles(dir)) {
      return PreviewProjectType.JAVA;
    }

    return null;
  }

  /**
   * Generate a Dockerfile for full-stack projects with separate frontend/backend directories.
   * Supports all frontend (Node/Vite, Static HTML) × backend combinations.
   */
  private String fullStackDockerfile(Path workspace, FullStackLayout layout) {
    if (layout.frontendType() == PreviewProjectType.STATIC_HTML) {
      return fullStackStaticBackend(workspace, layout);
    }
    if (layout.backendType() == null) {
      // Single-package full-stack (e.g. React + Express in same package.json)
      return singlePackageFullStackDockerfile(workspace);
    }
    return switch (layout.backendType()) {
      case SPRING_BOOT -> fullStackNodeSpringBoot(workspace, layout);
      case PYTHON -> fullStackNodePython(workspace, layout);
      case GO -> fullStackNodeGo(workspace, layout);
      case RUST -> fullStackNodeRust(workspace, layout);
      case JAVA -> fullStackNodeJava(workspace, layout);
      default -> fullStackNodeNode(workspace, layout);
    };
  }

  /** Node frontend + Node backend in separate directories (no npm workspaces). */
  private String fullStackNodeNode(Path workspace, FullStackLayout layout) {
    String fe = layout.frontendDir();
    String be = layout.backendDir();
    boolean feIsRoot = ".".equals(fe); // frontend lives in project root
    int backendPort = 3000;

    // Try to detect backend port
    if (be != null) {
      Path beDir = workspace.resolve(be);
      try {
        JsonNode bePkg = mapper.readTree(beDir.resolve("package.json").toFile());
        backendPort = detectBackendPort(beDir, bePkg);
      } catch (IOException ignored) {
      }
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
      Path fePkg =
          feIsRoot
              ? workspace.resolve("package.json")
              : workspace.resolve(fe).resolve("package.json");
      if (Files.exists(fePkg) && !PreviewFileSignals.fileContainsPattern(fePkg, "\"vite\"")) {
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
      sb.append("CMD [\"sh\", \"-c\", \"(")
          .append(beStartCmd)
          .append(" 2>&1 | awk '{print \\\"[backend] \\\" $0; fflush()}') & ")
          .append("(cd ")
          .append(feCmd)
          .append(
              " && npx vite --host 0.0.0.0 --port 5173 2>&1 | awk '{print \\\"[frontend] \\\" $0;"
                  + " fflush()}')\"]\n");
    } else if (fe != null) {
      sb.append("EXPOSE 5173\n");
      sb.append("CMD [\"sh\", \"-c\", \"cd ")
          .append(feCmd)
          .append(" && npx vite --host 0.0.0.0 --port 5173\"]\n");
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
    sb.append("RUN apt-get update && apt-get install -y curl maven && \\\n");
    sb.append("    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \\\n");
    sb.append("    apt-get install -y nodejs && rm -rf /var/lib/apt/lists/*\n");

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
      sb.append("CMD [\"sh\", \"-c\", \"(cd /app/")
          .append(be)
          .append(
              " && mvn spring-boot:run -DskipTests -q 2>&1 | awk '{print \\\"[backend] \\\" $0;"
                  + " fflush()}') & ")
          .append("(cd /app/")
          .append(fe)
          .append(
              " && npx vite --host 0.0.0.0 --port 5173 2>&1 | awk '{print \\\"[frontend] \\\" $0;"
                  + " fflush()}')\"]\n");
    } else if (be != null) {
      sb.append("CMD [\"sh\", \"-c\", \"cd /app/")
          .append(be)
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
    sb.append("RUN apt-get update && apt-get install -y curl && \\\n");
    sb.append("    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \\\n");
    sb.append("    apt-get install -y nodejs && rm -rf /var/lib/apt/lists/*\n");

    // Install backend Python deps
    if (be != null) {
      Path beDir = workspace.resolve(be);
      sb.append("COPY ")
          .append(be)
          .append("/requirements.txt* ")
          .append(be)
          .append("/pyproject.toml* ./")
          .append(be)
          .append("/\n");
      if (Files.exists(beDir.resolve("requirements.txt"))) {
        sb.append("RUN cd ")
            .append(be)
            .append(" && pip install --no-cache-dir -r requirements.txt\n");
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
      sb.append("CMD [\"sh\", \"-c\", \"(cd /app/")
          .append(be)
          .append(" && ")
          .append(pythonCmd)
          .append(" 2>&1 | awk '{print \\\"[backend] \\\" $0; fflush()}') & ")
          .append("(cd /app/")
          .append(fe)
          .append(
              " && npx vite --host 0.0.0.0 --port 5173 2>&1 | awk '{print \\\"[frontend] \\\" $0;"
                  + " fflush()}')\"]\n");
    } else if (be != null) {
      sb.append("CMD [\"sh\", \"-c\", \"cd /app/")
          .append(be)
          .append(" && ")
          .append(pythonCmd)
          .append("\"]\n");
    }

    return sb.toString();
  }

  /** Node frontend + Go backend (Docker Compose). */
  private String fullStackNodeGo(Path workspace, FullStackLayout layout) {
    String fe = layout.frontendDir();
    String be = layout.backendDir();

    var sb = new StringBuilder();
    sb.append("FROM golang:1.22-alpine\nWORKDIR /app\nLABEL preview=true\n");
    sb.append("RUN apk add --no-cache nodejs npm\n");

    if (be != null) {
      sb.append("COPY ")
          .append(be)
          .append("/go.mod ")
          .append(be)
          .append("/go.sum* ./")
          .append(be)
          .append("/\n");
      sb.append("RUN cd ").append(be).append(" && go mod download 2>/dev/null || true\n");
    }
    if (fe != null) {
      sb.append("COPY ").append(fe).append("/package*.json ./").append(fe).append("/\n");
      sb.append("RUN cd ").append(fe).append(" && npm install\n");
    }

    sb.append("COPY . .\n");
    sb.append("EXPOSE 5173 8080\n");

    if (fe != null && be != null) {
      sb.append("CMD [\"sh\", \"-c\", \"(cd /app/")
          .append(be)
          .append(" && go run . 2>&1 | awk '{print \\\"[backend] \\\" $0; fflush()}') & ")
          .append("(cd /app/")
          .append(fe)
          .append(
              " && npx vite --host 0.0.0.0 --port 5173 2>&1 | awk '{print \\\"[frontend] \\\" $0;"
                  + " fflush()}')\"]\n");
    }
    return sb.toString();
  }

  /** Node frontend + Rust backend (Docker Compose). */
  private String fullStackNodeRust(Path workspace, FullStackLayout layout) {
    String fe = layout.frontendDir();
    String be = layout.backendDir();

    var sb = new StringBuilder();
    sb.append("FROM rust:1.77-slim\nWORKDIR /app\nLABEL preview=true\n");
    sb.append("RUN apt-get update && apt-get install -y curl pkg-config libssl-dev && \\\n");
    sb.append("    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \\\n");
    sb.append("    apt-get install -y nodejs && rm -rf /var/lib/apt/lists/*\n");

    if (be != null) {
      sb.append("COPY ")
          .append(be)
          .append("/Cargo.toml ")
          .append(be)
          .append("/Cargo.lock* ./")
          .append(be)
          .append("/\n");
      sb.append("RUN cd ")
          .append(be)
          .append(
              " && mkdir src && echo \"fn main() {}\" > src/main.rs && cargo build --release"
                  + " 2>/dev/null || true && rm -rf src\n");
    }
    if (fe != null) {
      sb.append("COPY ").append(fe).append("/package*.json ./").append(fe).append("/\n");
      sb.append("RUN cd ").append(fe).append(" && npm install\n");
    }

    sb.append("COPY . .\n");
    if (be != null) {
      sb.append("RUN cd ").append(be).append(" && cargo build --release 2>/dev/null || true\n");
    }
    sb.append("EXPOSE 5173 8080\n");

    if (fe != null && be != null) {
      sb.append("CMD [\"sh\", \"-c\", \"(cd /app/")
          .append(be)
          .append(
              " && bin=$(find target/release -maxdepth 1 -type f -executable | head -1) && if [ -n"
                  + " \\\"$bin\\\" ]; then exec \\\"$bin\\\"; else cargo run --release; fi")
          .append(" 2>&1 | awk '{print \\\"[backend] \\\" $0; fflush()}') & ")
          .append("(cd /app/")
          .append(fe)
          .append(
              " && npx vite --host 0.0.0.0 --port 5173 2>&1 | awk '{print \\\"[frontend] \\\" $0;"
                  + " fflush()}')\"]\n");
    }
    return sb.toString();
  }

  /** Node frontend + Java (non-Spring) backend (Docker Compose). */
  private String fullStackNodeJava(Path workspace, FullStackLayout layout) {
    String fe = layout.frontendDir();
    String be = layout.backendDir();

    String mainClass = "Main";
    if (be != null) {
      Path beDir = workspace.resolve(be);
      try (Stream<Path> stream = Files.walk(beDir, 3)) {
        Path mainFile =
            stream
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> PreviewFileSignals.fileContainsPattern(p, "public static void main"))
                .findFirst()
                .orElse(null);
        if (mainFile != null) {
          String filename = mainFile.getFileName().toString();
          mainClass = filename.substring(0, filename.length() - 5);
        }
      } catch (IOException ignored) {
      }
    }

    var sb = new StringBuilder();
    sb.append("FROM eclipse-temurin:21\nWORKDIR /app\nLABEL preview=true\n");
    sb.append("RUN apt-get update && apt-get install -y curl && \\\n");
    sb.append("    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \\\n");
    sb.append("    apt-get install -y nodejs && rm -rf /var/lib/apt/lists/*\n");

    if (fe != null) {
      sb.append("COPY ").append(fe).append("/package*.json ./").append(fe).append("/\n");
      sb.append("RUN cd ").append(fe).append(" && npm install\n");
    }

    sb.append("COPY . .\n");
    if (be != null) {
      sb.append("RUN cd ").append(be).append(" && javac *.java 2>/dev/null || true\n");
    }
    sb.append("EXPOSE 5173 8080\n");

    if (fe != null && be != null) {
      sb.append("CMD [\"sh\", \"-c\", \"(cd /app/")
          .append(be)
          .append(" && java ")
          .append(mainClass)
          .append(" 2>&1 | awk '{print \\\"[backend] \\\" $0; fflush()}') & ")
          .append("(cd /app/")
          .append(fe)
          .append(
              " && npx vite --host 0.0.0.0 --port 5173 2>&1 | awk '{print \\\"[frontend] \\\" $0;"
                  + " fflush()}')\"]\n");
    }
    return sb.toString();
  }

  /** Static HTML frontend + any backend (Docker Compose). Uses nginx for static + proxy. */
  private String fullStackStaticBackend(Path workspace, FullStackLayout layout) {
    String fe = layout.frontendDir();
    String be = layout.backendDir();
    boolean feIsRoot = ".".equals(fe);
    PreviewProjectType beType = layout.backendType();

    boolean alpine = (beType == PreviewProjectType.GO || beType == PreviewProjectType.NODE_BACKEND);

    var sb = new StringBuilder();

    switch (beType) {
      case GO -> sb.append("FROM golang:1.22-alpine\n");
      case NODE_BACKEND -> sb.append("FROM node:20-alpine\n");
      case SPRING_BOOT -> sb.append("FROM eclipse-temurin:21\n");
      case PYTHON -> sb.append("FROM python:3.12-slim\n");
      case RUST -> sb.append("FROM rust:1.77-slim\n");
      case JAVA -> sb.append("FROM eclipse-temurin:21\n");
      default -> sb.append("FROM node:20-alpine\n");
    }

    sb.append("WORKDIR /app\nLABEL preview=true\n");

    if (alpine) {
      sb.append("RUN apk add --no-cache nginx\n");
    } else {
      sb.append("RUN apt-get update && apt-get install -y nginx && rm -rf /var/lib/apt/lists/*\n");
    }

    // Install backend deps (same logic as Cloud Run version)
    if (be != null) {
      Path beDir = workspace.resolve(be);
      switch (beType) {
        case GO -> {
          sb.append("COPY ")
              .append(be)
              .append("/go.mod ")
              .append(be)
              .append("/go.sum* ./")
              .append(be)
              .append("/\n");
          sb.append("RUN cd ").append(be).append(" && go mod download 2>/dev/null || true\n");
        }
        case NODE_BACKEND -> {
          sb.append("COPY ").append(be).append("/package*.json ./").append(be).append("/\n");
          sb.append("RUN cd ").append(be).append(" && npm install\n");
        }
        case SPRING_BOOT -> {
          sb.append(
              "RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*\n");
          sb.append("COPY ").append(be).append("/pom.xml ./").append(be).append("/\n");
          sb.append("RUN cd ").append(be).append(" && mvn dependency:go-offline -q -B || true\n");
        }
        case PYTHON -> {
          if (Files.exists(beDir.resolve("requirements.txt"))) {
            sb.append("COPY ").append(be).append("/requirements.txt ./").append(be).append("/\n");
            sb.append("RUN cd ")
                .append(be)
                .append(" && pip install --no-cache-dir -r requirements.txt\n");
          } else if (Files.exists(beDir.resolve("pyproject.toml"))) {
            sb.append("COPY ").append(be).append("/pyproject.toml ./").append(be).append("/\n");
            sb.append("RUN cd ").append(be).append(" && pip install --no-cache-dir .\n");
          }
        }
        case RUST -> {
          sb.append(
              "RUN apt-get update && apt-get install -y pkg-config libssl-dev && rm -rf"
                  + " /var/lib/apt/lists/*\n");
          sb.append("COPY ")
              .append(be)
              .append("/Cargo.toml ")
              .append(be)
              .append("/Cargo.lock* ./")
              .append(be)
              .append("/\n");
          sb.append("RUN cd ")
              .append(be)
              .append(
                  " && mkdir src && echo \"fn main() {}\" > src/main.rs && cargo build --release"
                      + " 2>/dev/null || true && rm -rf src\n");
        }
        case JAVA -> {}
        default -> {}
      }
    }

    sb.append("COPY . .\n");

    if (be != null) {
      switch (beType) {
        case RUST ->
            sb.append("RUN cd ")
                .append(be)
                .append(" && cargo build --release 2>/dev/null || true\n");
        case JAVA ->
            sb.append("RUN cd ").append(be).append(" && javac *.java 2>/dev/null || true\n");
        case GO ->
            sb.append("RUN cd ")
                .append(be)
                .append(" && go build -o server . 2>/dev/null || true\n");
        default -> {}
      }
    }

    // nginx config: serve static on 8080, proxy /api to backend on 8081
    String staticSrc = feIsRoot ? "/app" : "/app/" + fe;
    sb.append("RUN mkdir -p /usr/share/nginx/html && cp -r ")
        .append(staticSrc)
        .append("/*.html ")
        .append(staticSrc)
        .append("/*.css* ")
        .append(staticSrc)
        .append("/*.js* /usr/share/nginx/html/ 2>/dev/null || true\n");
    sb.append("RUN echo 'server { listen 8080; root /usr/share/nginx/html; index index.html; ")
        .append("location / { try_files \\$uri \\$uri/ /index.html =404; } ")
        .append(
            "location /api { proxy_pass http://127.0.0.1:8081; } }' >"
                + " /etc/nginx/conf.d/default.conf\n");

    int backendPort = beType.getContainerPort();
    sb.append("EXPOSE 8080 ").append(backendPort).append("\n");

    String backendCmd = crDetectStaticBackendCmd(workspace, layout);
    sb.append("CMD [\"sh\", \"-c\", \"(")
        .append(backendCmd)
        .append(
            " 2>&1 | awk '{print \\\"[backend] \\\" $0; fflush()}') & nginx -g 'daemon off;'\"]\n");

    return sb.toString();
  }

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
   * For monorepos: determine the dominant project type by scanning sub-packages. If any sub-package
   * has a backend dep, treat as NODE_FRONTEND (preview the frontend). The backend runs alongside
   * it.
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
          if (PreviewDependencySignals.hasFrontendDependency(deps)) hasFrontend = true;
          if (PreviewDependencySignals.hasBackendDependency(deps)) hasBackend = true;
        }
        JsonNode devDeps = pkg.get("devDependencies");
        if (PreviewDependencySignals.hasMonorepoFrontendSignal(null, devDeps)) {
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
   * Generate a Dockerfile for monorepo projects. Copies all sub-package.json files, installs with
   * workspaces, starts all services.
   */
  private String monorepoDockerfile(JsonNode rootPkg, Path workspace, PreviewProjectType type) {
    var sb = new StringBuilder();
    sb.append(
        """
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
        sb.append("COPY ").append(wsName).append("/package.json ./").append(wsName).append("/\n");
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

        boolean isFrontend = PreviewDependencySignals.hasMonorepoFrontendSignal(deps, devDeps);
        boolean isBackend = PreviewDependencySignals.hasBackendDependency(deps);

        if (isFrontend && frontendWs == null) {
          frontendWs = wsName;
          // Check vite config for custom port
          if (Files.exists(wsDir.resolve("vite.config.js"))
              || Files.exists(wsDir.resolve("vite.config.ts"))) {
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
          .append(backendWs)
          .append(" 2>&1 | awk '{print \\\"[backend] \\\" $0; fflush()}') & ")
          .append("(npx --prefix ")
          .append(frontendWs)
          .append(" vite --host 0.0.0.0 --port ")
          .append(frontendPort)
          .append(" 2>&1 | awk '{print \\\"[frontend] \\\" $0; fflush()}')\"]\n");
    } else if (frontendWs != null) {
      sb.append("EXPOSE ").append(frontendPort).append("\n");
      sb.append("CMD [\"npx\", \"--prefix\", \"")
          .append(frontendWs)
          .append("\", \"vite\", \"--host\", \"0.0.0.0\", \"--port\", \"")
          .append(frontendPort)
          .append("\"]\n");
    } else if (backendWs != null) {
      sb.append("EXPOSE ").append(backendPort).append("\n");
      sb.append("CMD [\"npm\", \"run\", \"dev\", \"--prefix\", \"")
          .append(backendWs)
          .append("\"]\n");
    } else {
      // Fallback: try root dev script
      sb.append("EXPOSE ").append(type.getContainerPort()).append("\n");
      sb.append("CMD [\"npm\", \"run\", \"dev\"]\n");
    }

    return sb.toString();
  }

  /** Try to detect the port a backend server listens on by scanning its main file. */
  private int detectBackendPort(Path wsDir, JsonNode pkg) {
    return PreviewNodeSignals.detectBackendPort(wsDir, pkg);
  }

  private String goDockerfile(Path workspace) {
    return PreviewStandaloneDockerfiles.go();
  }

  private String rustDockerfile(Path workspace) {
    return PreviewStandaloneDockerfiles.rust();
  }

  /**
   * React Native + Expo preview Dockerfile. Two-stage build:
   *
   * <ol>
   *   <li>builder — runs {@code npx expo export --platform android} to produce the Metro bundle +
   *       asset manifest under {@code /app/dist/}.
   *   <li>runtime — nginx serves the bundle + a custom landing page that explains the preview
   *       status, shows bundle size, and renders a client-side QR code pointing to the Expo Go
   *       deep-link. Full Expo Go protocol integration (manifest protocol v2 + on-device hot
   *       reload) is v2 follow-up — this v1 Dockerfile just proves the bundle builds + makes the
   *       artifact downloadable from the preview URL.
   * </ol>
   *
   * <p>Workspace-independent (identical bytes for every RN run) so Cloud Run can cache the builder
   * layer across previews.
   */
  private String reactNativeExpoDockerfile() {
    return PreviewStandaloneDockerfiles.reactNativeExpo();
  }

  private String staticHtmlDockerfile() {
    return PreviewStandaloneDockerfiles.staticHtml();
  }

  /**
   * Detect how a project should be previewed: as a web app (direct HTTP), a CLI app (wrapped by
   * ttyd), or an MCP server (wrapped by Inspector).
   */
  public PreviewMode detectMode(PreviewProjectType type, Path workspace) {
    return PreviewModeDetector.detect(type, workspace);
  }

  /** Generate Dockerfile content for the given project type and preview mode. */
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
      default -> generate(type, workspace); // fallback to web
    };
  }

  private String cliNodeDockerfile(Path workspace) {
    String entryFile = findNodeEntryPoint(workspace);
    String installStep =
        Files.exists(workspace.resolve("package.json")) ? "RUN npm install" : "# No package.json";
    return """
           FROM node:20-alpine
           WORKDIR /app
           LABEL preview=true
           RUN apk add --no-cache ttyd
           COPY package*.json* ./
           """
        + installStep
        + "\n"
        + """
          COPY . .
          EXPOSE 8080
          CMD ["ttyd", "--port", "8080", "--writable", "node", \""""
        + entryFile
        + "\"]\n";
  }

  private String cliJavaDockerfile(Path workspace) {
    // Find the java file containing 'public static void main'
    String mainClass = "Main";
    try (Stream<Path> stream = Files.walk(workspace, 3)) {
      Path mainFile =
          stream
              .filter(p -> p.toString().endsWith(".java"))
              .filter(p -> PreviewFileSignals.fileContainsPattern(p, "public static void main"))
              .findFirst()
              .orElse(null);
      if (mainFile != null) {
        // If it's a single file at root, mainClass is just the name without .java
        String filename = mainFile.getFileName().toString();
        mainClass = filename.substring(0, filename.length() - 5);
      }
    } catch (IOException e) {
      /* ignore */
    }

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
CMD ["ttyd", "--port", "8080", "--writable", "java", \""""
        + mainClass
        + "\"]\n";
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
"""
        + installStep
        + "\n"
        + """
          COPY . .
          EXPOSE 8080
          CMD ["ttyd", "--port", "8080", "--writable", "python", \""""
        + entryFile
        + "\"]\n";
  }

  private String cliGoDockerfile() {
    return PreviewStandaloneDockerfiles.cliGo();
  }

  private String cliRustDockerfile() {
    return PreviewStandaloneDockerfiles.cliRust();
  }

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
           CMD ["sh", "-c", "npx @modelcontextprotocol/inspector --port 8080 -- """
        + serverCmd
        + "\"]\n";
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
           RUN apt-get update && apt-get install -y curl && \
               curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
               apt-get install -y nodejs && rm -rf /var/lib/apt/lists/*
           RUN npm install -g @modelcontextprotocol/inspector
           COPY requirements.txt* pyproject.toml* ./
           """
        + installStep
        + "\n"
        + """
          COPY . .
          EXPOSE 8080
          ENV CLIENT_PORT=8080
          ENV SERVER_PORT=9315
          CMD ["sh", "-c", "npx @modelcontextprotocol/inspector --port 8080 -- python """
        + entryFile
        + "\"]\n";
  }

  private JsonNode parsePackageJson(Path workspace) {
    return PreviewNodeSignals.parsePackageJson(workspace);
  }

  /**
   * Check if a Python dependency exists in requirements.txt or pyproject.toml. More reliable than
   * scanning source files (catches package imports in subdirectories).
   */
  private boolean hasDependency(Path workspace, String packageName) {
    return PreviewPythonSignals.hasDependency(workspace, packageName);
  }

  private List<Path> findPythonFiles(Path workspace) {
    return PreviewPythonSignals.findPythonFiles(workspace);
  }

  /**
   * Find the first Python file in workspace root that contains any of the given patterns. Returns
   * the filename (e.g. "app.py"), or null if none found.
   */
  private String findPythonFileWithPattern(Path workspace, String... patterns) {
    return PreviewPythonSignals.findPythonFileWithPattern(workspace, patterns);
  }

  /** Find the Node.js entry point file. Checks common names in priority order. */
  private String findNodeEntryPoint(Path workspace) {
    return PreviewNodeSignals.findEntryPoint(workspace);
  }

  /**
   * Find Node entry point in a specific directory (not workspace root). Used for backend
   * subdirectories that may lack their own package.json.
   */
  private String findNodeEntryPointInDir(Path dir) {
    return PreviewNodeSignals.findEntryPointInDir(dir);
  }

  /**
   * Find the Python entry point file. Checks common names first, then scans for files containing
   * {@code if __name__}.
   */
  private String findPythonEntryPoint(Path workspace) {
    return PreviewPythonSignals.findEntryPoint(workspace);
  }
}
