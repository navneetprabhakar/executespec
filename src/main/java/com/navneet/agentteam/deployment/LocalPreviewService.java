package com.navneet.agentteam.deployment;

import com.navneet.agentteam.config.AgentProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages local process-based previews for completed pipeline runs.
 *
 * <p>For each run the appropriate dev server is started directly in the workspace
 * (e.g. {@code mvn spring-boot:run}, {@code npm run dev}) on a free local port.
 * The resulting URL is served to the UI which embeds it in an iframe or shows
 * an OpenAPI viewer for backend-only projects.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LocalPreviewService {

    private final AgentProperties agentProps;

    // ── State ──────────────────────────────────────────────────────────────────

    public enum PreviewStatus { IDLE, STARTING, RUNNING, FAILED, STOPPED }

    public record ProcessInfo(
        Process process,
        long pid,
        int port,
        String label          // "backend" | "frontend" | "app"
    ) {}

    public record PreviewState(
        String runId,
        PreviewStatus status,
        String url,
        String projectType,
        String errorMessage,
        List<ProcessInfo> processes,
        String backendUrl,
        String frontendUrl,
        CircularBuffer outputLog
    ) {}

    private final ConcurrentHashMap<String, PreviewState> states = new ConcurrentHashMap<>();

    // ── Public API ─────────────────────────────────────────────────────────────

    public PreviewState getStatus(String runId) {
        return states.getOrDefault(runId,
            new PreviewState(runId, PreviewStatus.IDLE, null, detectProjectType(runId),
                             null, List.of(), null, null, new CircularBuffer(1)));
    }

    /**
     * Start preview process(es) asynchronously. Returns immediately;
     * poll {@link #getStatus} to track progress.
     */
    public void startAsync(String runId) {
        // If already running, stop first
        PreviewState existing = states.get(runId);
        if (existing != null && existing.status() == PreviewStatus.RUNNING) {
            stop(runId);
        }

        String projectType = detectProjectType(runId);
        int bufferSize = agentProps.getPreview().getLogBufferLines();
        CircularBuffer logBuffer = new CircularBuffer(bufferSize);

        states.put(runId, new PreviewState(
            runId, PreviewStatus.STARTING, null, projectType, null,
            List.of(), null, null, logBuffer));

        Thread.ofVirtual().name("preview-" + runId.substring(0, 8)).start(() -> {
            try {
                Path workspace = workspaceRoot(runId);
                if (!Files.isDirectory(workspace)) {
                    throw new IllegalStateException("Workspace not found: " + workspace);
                }

                switch (projectType) {
                    case "FULLSTACK"   -> startFullStack(runId, workspace, logBuffer);
                    case "SPRING_BOOT" -> startSingleProcess(runId, workspace, logBuffer, projectType,
                                            springBootCommand(findFreePort()));
                    case "GRADLE"      -> startSingleProcess(runId, workspace, logBuffer, projectType,
                                            gradleCommand(workspace, findFreePort()));
                    case "NODE"        -> startSingleProcess(runId, workspace, logBuffer, projectType,
                                            nodeCommand(workspace, findFreePort()));
                    case "PYTHON"      -> startSingleProcess(runId, workspace, logBuffer, projectType,
                                            pythonCommand(workspace, findFreePort()));
                    case "GO"          -> startSingleProcess(runId, workspace, logBuffer, projectType,
                                            goCommand(findFreePort()));
                    case "RUBY"        -> startSingleProcess(runId, workspace, logBuffer, projectType,
                                            rubyCommand(findFreePort()));
                    case "PHP"         -> startSingleProcess(runId, workspace, logBuffer, projectType,
                                            phpCommand(findFreePort()));
                    case "RUST"        -> startSingleProcess(runId, workspace, logBuffer, projectType,
                                            rustCommand(findFreePort()));
                    case "DOTNET"      -> startSingleProcess(runId, workspace, logBuffer, projectType,
                                            dotnetCommand(findFreePort()));
                    case "STATIC"      -> startSingleProcess(runId, workspace, logBuffer, projectType,
                                            staticCommand(findFreePort()));
                    default -> throw new IllegalStateException(
                        "Cannot preview project type: " + projectType +
                        ". No recognised build files (pom.xml, package.json, etc.) found.");
                }

            } catch (Exception e) {
                log.error("[preview] Failed to start preview for run {}: {}", runId, e.getMessage(), e);
                PreviewState s = states.get(runId);
                states.put(runId, new PreviewState(
                    runId, PreviewStatus.FAILED, null, projectType, e.getMessage(),
                    s != null ? s.processes() : List.of(), null, null, logBuffer));
            }
        });
    }

    /** Stop all processes for a run. */
    public void stop(String runId) {
        PreviewState state = states.get(runId);
        if (state != null) {
            for (ProcessInfo pi : state.processes()) {
                killProcessTree(pi);
            }
        }
        String projectType = state != null ? state.projectType() : detectProjectType(runId);
        CircularBuffer logBuffer = state != null ? state.outputLog() : new CircularBuffer(1);
        states.put(runId, new PreviewState(
            runId, PreviewStatus.STOPPED, null, projectType, null,
            List.of(), null, null, logBuffer));
        log.info("[preview] Stopped all processes for run {}", runId);
    }

    /** Get the last N lines of process output. */
    public List<String> getLogs(String runId, int maxLines) {
        PreviewState state = states.get(runId);
        if (state == null || state.outputLog() == null) return List.of();
        return state.outputLog().getLast(maxLines);
    }

    @PreDestroy
    public void shutdownAll() {
        states.forEach((runId, state) -> {
            if (state.status() == PreviewStatus.RUNNING || state.status() == PreviewStatus.STARTING) {
                log.info("[preview] Shutting down preview processes for run {}", runId);
                for (ProcessInfo pi : state.processes()) {
                    killProcessTree(pi);
                }
            }
        });
        states.clear();
    }

    // ── Single-process start ───────────────────────────────────────────────────

    private void startSingleProcess(String runId, Path workspace, CircularBuffer logBuffer,
                                     String projectType, ProcessCommand cmd) throws Exception {
        ProcessInfo pi = launchProcess(workspace, cmd, logBuffer);

        states.put(runId, new PreviewState(
            runId, PreviewStatus.STARTING, null, projectType, null,
            List.of(pi), null, null, logBuffer));

        String url = "http://localhost:" + cmd.port();
        if (waitForReady(url, agentProps.getPreview().getStartupTimeoutSeconds(), pi)) {
            log.info("[preview] Process running for run {} → {}", runId, url);
            states.put(runId, new PreviewState(
                runId, PreviewStatus.RUNNING, url, projectType, null,
                List.of(pi), null, null, logBuffer));
        } else {
            String lastLines = String.join("\n", logBuffer.getLast(20));
            killProcessTree(pi);
            throw new IllegalStateException(
                "Process did not start serving within " +
                agentProps.getPreview().getStartupTimeoutSeconds() + "s.\n" + lastLines);
        }
    }

    // ── Full-stack start (backend + frontend) ──────────────────────────────────

    private void startFullStack(String runId, Path workspace, CircularBuffer logBuffer) throws Exception {
        String projectType = "FULLSTACK";
        int backendPort = findFreePort();
        int frontendPort = findFreePort();

        // Determine backend type and path
        Path backendDir;
        ProcessCommand backendCmd;
        if (Files.exists(workspace.resolve("backend/pom.xml"))) {
            backendDir = workspace.resolve("backend");
            backendCmd = springBootCommand(backendPort);
        } else if (Files.exists(workspace.resolve("backend/build.gradle"))
                || Files.exists(workspace.resolve("backend/build.gradle.kts"))) {
            backendDir = workspace.resolve("backend");
            backendCmd = gradleCommand(backendDir, backendPort);
        } else if (Files.exists(workspace.resolve("pom.xml"))) {
            backendDir = workspace;
            backendCmd = springBootCommand(backendPort);
        } else {
            backendDir = workspace;
            backendCmd = springBootCommand(backendPort);
        }

        // Frontend is always Node-based in fullstack
        Path frontendDir = Files.exists(workspace.resolve("frontend/package.json"))
            ? workspace.resolve("frontend")
            : workspace;

        ProcessCommand frontendCmd = nodeCommand(frontendDir, frontendPort);

        ProcessInfo backendPi = launchProcess(backendDir, backendCmd, logBuffer);
        ProcessInfo frontendPi = launchProcess(frontendDir, frontendCmd, logBuffer);

        List<ProcessInfo> processes = List.of(backendPi, frontendPi);
        states.put(runId, new PreviewState(
            runId, PreviewStatus.STARTING, null, projectType, null,
            processes, null, null, logBuffer));

        String backendUrl = "http://localhost:" + backendPort;
        String frontendUrl = "http://localhost:" + frontendPort;

        // Wait for both
        boolean backendOk = waitForReady(backendUrl, agentProps.getPreview().getStartupTimeoutSeconds(), backendPi);
        boolean frontendOk = waitForReady(frontendUrl, agentProps.getPreview().getStartupTimeoutSeconds(), frontendPi);

        if (backendOk && frontendOk) {
            log.info("[preview] Full-stack running for run {} → backend={}, frontend={}", runId, backendUrl, frontendUrl);
            states.put(runId, new PreviewState(
                runId, PreviewStatus.RUNNING, frontendUrl, projectType, null,
                processes, backendUrl, frontendUrl, logBuffer));
        } else {
            String err = (!backendOk ? "Backend failed to start. " : "") +
                         (!frontendOk ? "Frontend failed to start. " : "") +
                         String.join("\n", logBuffer.getLast(20));
            processes.forEach(this::killProcessTree);
            throw new IllegalStateException(err);
        }
    }

    // ── Command builders ───────────────────────────────────────────────────────

    record ProcessCommand(String[] command, Map<String, String> env, int port, String label) {}

    private static ProcessCommand springBootCommand(int port) {
        return new ProcessCommand(
            new String[]{"mvn", "spring-boot:run",
                "-Dspring-boot.run.arguments=--server.port=" + port, "-q"},
            Map.of(), port, "backend");
    }

    private static ProcessCommand gradleCommand(Path workspace, int port) {
        String wrapper = Files.exists(workspace.resolve("gradlew")) ? "./gradlew" : "gradle";
        return new ProcessCommand(
            new String[]{wrapper, "bootRun", "--args=--server.port=" + port, "-q"},
            Map.of(), port, "backend");
    }

    private static ProcessCommand nodeCommand(Path workspace, int port) {
        // Prefer "dev" script (Vite/Next.js dev mode), fall back to "start"
        String script = "start";
        try {
            String pkgJson = Files.readString(workspace.resolve("package.json"));
            if (pkgJson.contains("\"dev\"")) script = "dev";
        } catch (IOException ignored) {}

        return new ProcessCommand(
            new String[]{"npm", "run", script},
            Map.of("PORT", String.valueOf(port)), port, "frontend");
    }

    private static ProcessCommand pythonCommand(Path workspace, int port) {
        // Detect framework from requirements
        String requirements = "";
        try {
            if (Files.exists(workspace.resolve("requirements.txt"))) {
                requirements = Files.readString(workspace.resolve("requirements.txt")).toLowerCase();
            }
        } catch (IOException ignored) {}

        if (requirements.contains("uvicorn") || requirements.contains("fastapi")) {
            // Find the main module — common patterns: main.py, app.py, app/main.py
            String module = "main:app";
            if (Files.exists(workspace.resolve("app/main.py"))) module = "app.main:app";
            else if (Files.exists(workspace.resolve("app.py"))) module = "app:app";
            return new ProcessCommand(
                new String[]{"uvicorn", module, "--host", "0.0.0.0", "--port", String.valueOf(port)},
                Map.of(), port, "app");
        } else if (requirements.contains("flask")) {
            return new ProcessCommand(
                new String[]{"flask", "run", "--host", "0.0.0.0", "--port", String.valueOf(port)},
                Map.of("FLASK_APP", detectFlaskApp(workspace)), port, "app");
        } else {
            // Generic Python — run main.py or app.py with PORT env
            String entrypoint = Files.exists(workspace.resolve("main.py")) ? "main.py" : "app.py";
            return new ProcessCommand(
                new String[]{"python3", entrypoint},
                Map.of("PORT", String.valueOf(port)), port, "app");
        }
    }

    private static String detectFlaskApp(Path workspace) {
        if (Files.exists(workspace.resolve("app.py"))) return "app";
        if (Files.exists(workspace.resolve("main.py"))) return "main";
        if (Files.exists(workspace.resolve("app/__init__.py"))) return "app";
        return "app";
    }

    private static ProcessCommand goCommand(int port) {
        return new ProcessCommand(
            new String[]{"go", "run", "."},
            Map.of("PORT", String.valueOf(port)), port, "app");
    }

    private static ProcessCommand rubyCommand(int port) {
        return new ProcessCommand(
            new String[]{"bundle", "exec", "rails", "server", "-p", String.valueOf(port), "-b", "0.0.0.0"},
            Map.of(), port, "app");
    }

    private static ProcessCommand phpCommand(int port) {
        return new ProcessCommand(
            new String[]{"php", "-S", "0.0.0.0:" + port},
            Map.of(), port, "app");
    }

    private static ProcessCommand rustCommand(int port) {
        return new ProcessCommand(
            new String[]{"cargo", "run"},
            Map.of("PORT", String.valueOf(port)), port, "app");
    }

    private static ProcessCommand dotnetCommand(int port) {
        return new ProcessCommand(
            new String[]{"dotnet", "run", "--urls=http://0.0.0.0:" + port},
            Map.of(), port, "app");
    }

    private static ProcessCommand staticCommand(int port) {
        return new ProcessCommand(
            new String[]{"python3", "-m", "http.server", String.valueOf(port)},
            Map.of(), port, "app");
    }

    // ── Process management ─────────────────────────────────────────────────────

    private ProcessInfo launchProcess(Path workDir, ProcessCommand cmd,
                                       CircularBuffer logBuffer) throws IOException {
        log.info("[preview] Launching in {}: {} (port {})",
            workDir, String.join(" ", cmd.command()), cmd.port());

        ProcessBuilder pb = new ProcessBuilder(cmd.command())
            .directory(workDir.toFile())
            .redirectErrorStream(true);

        // Merge env overrides
        pb.environment().putAll(cmd.env());

        Process process = pb.start();
        long pid = process.pid();

        // Attach output reader on a virtual thread
        Thread.ofVirtual().name("log-" + cmd.label() + "-" + pid).start(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    logBuffer.add("[" + cmd.label() + "] " + line);
                }
            } catch (IOException e) {
                logBuffer.add("[" + cmd.label() + "] Output stream closed: " + e.getMessage());
            }
        });

        log.info("[preview] Started {} process PID={} on port {}", cmd.label(), pid, cmd.port());
        return new ProcessInfo(process, pid, cmd.port(), cmd.label());
    }

    /**
     * Poll the given URL. Returns true once a 2xx/3xx response is received,
     * false if timeout expires or the process dies.
     */
    private boolean waitForReady(String url, int timeoutSeconds, ProcessInfo pi) {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        while (System.currentTimeMillis() < deadline) {
            // Check if process is still alive
            if (!pi.process().isAlive()) {
                log.warn("[preview] Process PID={} exited before becoming ready (exit={})",
                    pi.pid(), pi.process().exitValue());
                return false;
            }

            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
                HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() < 400) {
                    return true;
                }
            } catch (Exception ignored) {
                // Connection refused, timeout, etc. — keep polling
            }

            try { Thread.sleep(2000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Kill a process and all its descendants. */
    private void killProcessTree(ProcessInfo pi) {
        try {
            // Kill descendants first (child processes spawned by Maven, npm, etc.)
            pi.process().descendants().forEach(ph -> {
                log.debug("[preview] Killing child PID={}", ph.pid());
                ph.destroyForcibly();
            });
            pi.process().destroyForcibly();
            pi.process().waitFor();
            log.info("[preview] Killed process tree PID={}", pi.pid());
        } catch (Exception e) {
            log.warn("[preview] Error killing process PID={}: {}", pi.pid(), e.getMessage());
        }
    }

    // ── Project type detection (unchanged) ─────────────────────────────────────

    String detectProjectType(String runId) {
        Path workspace = workspaceRoot(runId);
        if (!Files.isDirectory(workspace)) return "UNKNOWN";

        boolean hasPom         = Files.exists(workspace.resolve("pom.xml"));
        boolean hasGradle      = Files.exists(workspace.resolve("build.gradle"))
                              || Files.exists(workspace.resolve("build.gradle.kts"));
        boolean hasRootPkg     = Files.exists(workspace.resolve("package.json"));
        boolean hasBackendPom  = Files.exists(workspace.resolve("backend/pom.xml"));
        boolean hasBackendGradle = Files.exists(workspace.resolve("backend/build.gradle"))
                              || Files.exists(workspace.resolve("backend/build.gradle.kts"));
        boolean hasBackendNode = Files.exists(workspace.resolve("backend/package.json"));
        boolean hasFrontendPkg = Files.exists(workspace.resolve("frontend/package.json"));
        boolean hasNode        = hasRootPkg || hasFrontendPkg;
        boolean hasPython      = Files.exists(workspace.resolve("requirements.txt"))
                              || Files.exists(workspace.resolve("setup.py"))
                              || Files.exists(workspace.resolve("pyproject.toml"))
                              || Files.exists(workspace.resolve("Pipfile"));
        boolean hasGo          = Files.exists(workspace.resolve("go.mod"));
        boolean hasRuby        = Files.exists(workspace.resolve("Gemfile"));
        boolean hasPhp         = Files.exists(workspace.resolve("composer.json"));
        boolean hasRust        = Files.exists(workspace.resolve("Cargo.toml"));
        boolean hasDotNet      = hasCsprojOrSln(workspace);
        boolean hasStatic      = Files.exists(workspace.resolve("index.html"));

        if ((hasBackendPom || hasBackendGradle || hasBackendNode) && hasFrontendPkg) return "FULLSTACK";
        if (hasPom && hasNode)  return "FULLSTACK";
        if (hasPom)             return "SPRING_BOOT";
        if (hasGradle)          return "GRADLE";
        if (hasRootPkg)         return "NODE";
        if (hasPython)          return "PYTHON";
        if (hasGo)              return "GO";
        if (hasRuby)            return "RUBY";
        if (hasPhp)             return "PHP";
        if (hasRust)            return "RUST";
        if (hasDotNet)          return "DOTNET";
        if (hasStatic)          return "STATIC";
        return "UNKNOWN";
    }

    private static boolean hasCsprojOrSln(Path workspace) {
        try (var stream = Files.list(workspace)) {
            return stream.anyMatch(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".csproj") || n.endsWith(".sln");
            });
        } catch (IOException e) {
            return false;
        }
    }

    private Path workspaceRoot(String runId) {
        return Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    // ── CircularBuffer (thread-safe ring buffer) ───────────────────────────────

    public static class CircularBuffer {
        private final String[] buffer;
        private int head = 0;
        private int count = 0;

        public CircularBuffer(int capacity) {
            this.buffer = new String[Math.max(1, capacity)];
        }

        public synchronized void add(String item) {
            buffer[head] = item;
            head = (head + 1) % buffer.length;
            if (count < buffer.length) count++;
        }

        public synchronized List<String> getAll() {
            return getLast(count);
        }

        public synchronized List<String> getLast(int n) {
            int take = Math.min(n, count);
            List<String> result = new ArrayList<>(take);
            int start = (head - take + buffer.length) % buffer.length;
            for (int i = 0; i < take; i++) {
                result.add(buffer[(start + i) % buffer.length]);
            }
            return result;
        }
    }
}
