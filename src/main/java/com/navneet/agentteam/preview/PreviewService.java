package com.navneet.agentteam.preview;

import com.navneet.agentteam.sse.RunEvent;
import com.navneet.agentteam.sse.SseBroadcaster;
import com.navneet.agentteam.state.StateStore;
import com.navneet.agentteam.state.entity.PipelineRun;
import com.navneet.agentteam.tool.ShellTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Post-merge service that builds the frontend (if any) and makes it available
 * for in-dashboard iframe preview via {@link PreviewController}.
 *
 * <p>
 * Skips when no frontend files (.html / .jsx / .tsx / .vue / .svelte) are found
 * in the workspace. For npm projects runs {@code npm install && npm run build}
 * and
 * auto-detects the output dir (dist / build / out / public / www). Plain HTML
 * projects
 * are served from the workspace root.
 *
 * <p>
 * Always runs on a virtual thread — non-fatal, never throws.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PreviewService {

    private final ShellTool shellTool;
    private final SseBroadcaster broadcaster;
    private final StateStore stateStore;
    private final PreviewProperties props;

    @Value("${agent.workspace}")
    private String workspaceRoot;

    private static final String[] FRONTEND_EXTENSIONS = { ".html", ".jsx", ".tsx", ".vue", ".svelte" };
    private static final List<String> NPM_OUTPUT_DIRS = List.of("dist", "build", "out", "public", "www");

    // ── Public API ────────────────────────────────────────────────────────────

    /** Called post-merge via virtual thread from PipelineHarness. */
    public void buildAsync(PipelineRun run) {
        Thread.ofVirtual()
                .name("preview-" + run.getRunId())
                .start(() -> safeBuild(run));
    }

    // ── Implementation ────────────────────────────────────────────────────────

    private void safeBuild(PipelineRun run) {
        try {
            build(run);
        } catch (Exception e) {
            log.error("PreviewService failed for run {}: {}", run.getRunId(), e.getMessage(), e);
        }
    }

    private void build(PipelineRun run) {
        if (!props.isEnabled())
            return;
        String runId = run.getRunId();

        Path ws = Path.of(workspaceRoot, runId);
        if (!hasFrontendFiles(ws)) {
            log.info("PreviewService: no frontend files in run {} — skipping", runId);
            return;
        }

        log.info("PreviewService: building frontend preview for run {}", runId);
        broadcaster.emit(runId, RunEvent.log(runId, "preview", "🌐 Building frontend preview..."));

        String serveDir = ""; // default: serve from workspace root

        if (Files.exists(ws.resolve("package.json"))) {
            // npm / Node project — install dependencies then build
            shellTool.run(runId, "npm install 2>&1", 180);
            String buildResult = shellTool.run(runId, "npm run build 2>&1", 300);
            if (buildResult.startsWith("ERROR")) {
                broadcaster.emit(runId, RunEvent.log(runId, "preview",
                        "⚠️ npm build failed — attempting to serve sources directly"));
            }
            String detected = detectNpmOutputDir(ws);
            if (detected != null) {
                serveDir = detected;
            } else {
                broadcaster.emit(runId, RunEvent.log(runId, "preview",
                        "⚠️ Could not detect npm build output dir — serving workspace root"));
            }
        }
        // else: plain HTML/CSS/JS — serve workspace root directly

        stateStore.markPreviewReady(runId, serveDir);
        broadcaster.emit(runId, RunEvent.previewReady(runId, serveDir));
        log.info("PreviewService: preview ready for run {} serveDir='{}'", runId, serveDir);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    boolean hasFrontendFiles(Path ws) {
        try (Stream<Path> s = Files.walk(ws)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("node_modules"))
                    .filter(p -> !p.toString().contains(".git"))
                    .anyMatch(p -> {
                        String name = p.getFileName().toString();
                        for (String ext : FRONTEND_EXTENSIONS) {
                            if (name.endsWith(ext))
                                return true;
                        }
                        return false;
                    });
        } catch (IOException e) {
            return false;
        }
    }

    private String detectNpmOutputDir(Path ws) {
        for (String dir : NPM_OUTPUT_DIRS) {
            if (Files.isDirectory(ws.resolve(dir)))
                return dir;
        }
        return null;
    }
}
