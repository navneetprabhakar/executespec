package com.navneet.executespec.previewrender;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

/**
 * Bounded, deterministic reader for the source files a preview render may consider.
 *
 * <p>Split out of {@link PreviewSurfaceDiscovery}, which owns classification and scoring — what
 * counts as a screen and which screens win. This class owns the step before that: which bytes are
 * allowed off disk at all, and in what order.
 *
 * <p>Two properties this has to get right, because the whole cost bound depends on them:
 *
 * <ol>
 *   <li>Skipped trees are pruned during the walk, not filtered afterwards. {@code node_modules}
 *       alone routinely holds more files than the cap allows, so a cap applied to the raw file
 *       stream would be spent entirely on directories that were never candidates — and an ordinary
 *       JS repo would report no screens at all.
 *   <li>The surviving set is sorted by relative path before anything is read. Directory iteration
 *       order is filesystem-dependent and unstable across machines and clones, so ordering by it
 *       would make both the selection and its token cost irreproducible.
 * </ol>
 */
@Slf4j
class PreviewWorkspaceScanner {

  /** Directories that never contain authored UI source. */
  private static final Set<String> SKIP_DIRS =
      Set.of(
          "node_modules",
          ".git",
          ".gradle",
          ".idea",
          "build",
          "dist",
          "out",
          "target",
          "vendor",
          "Pods",
          "DerivedData",
          "coverage",
          "__pycache__",
          ".next",
          ".expo",
          ".executespec",
          ".agent-context");

  private static final long MAX_FILE_BYTES = 128 * 1024L;

  /** How many candidate files are read into memory for scoring. */
  private static final int MAX_FILES_READ = 20_000;

  /**
   * Total characters held in memory across all files read for scoring (RR-304).
   *
   * <p>32 MiB of characters is roughly an order of magnitude more than the largest prompt budget a
   * render can spend, so it never truncates a realistic project, while capping what a single
   * request can retain on a shared JVM.
   */
  private static final long MAX_TOTAL_READ_CHARS = 32L * 1024 * 1024;

  /** Hard ceiling on collected paths, so a pathological tree cannot exhaust the heap. */
  private static final int MAX_CANDIDATE_PATHS = 200_000;

  /** Collects the readable candidate screens, in a stable order. */
  Map<String, String> readCandidateFiles(Path workspace) {
    List<Path> candidates = collectCandidatePaths(workspace);
    if (candidates == null) {
      return Map.of();
    }

    candidates.sort(Comparator.comparing(p -> p.toString().replace('\\', '/')));

    Map<String, String> out = new LinkedHashMap<>();
    long totalChars = 0;
    for (Path rel : candidates) {
      if (out.size() >= MAX_FILES_READ || totalChars >= MAX_TOTAL_READ_CHARS) {
        // RR-304: the file-count cap alone bounded nothing useful — 20,000 files at the 128 KiB
        // per-file ceiling is roughly 2.5 GB of retained String, enough for one render request to
        // OOM the shared JVM and take unrelated runs with it. The byte budget is the real bound;
        // the count cap stays as a secondary limit.
        log.info(
            "preview-render discovery stopped at {} files / {} chars (cap reached)",
            out.size(),
            totalChars);
        break;
      }
      try {
        Path abs = workspace.resolve(rel);
        long size = Files.size(abs);
        if (totalChars + size > MAX_TOTAL_READ_CHARS) {
          // Skip rather than break: later candidates may still be small enough to fit, and the
          // candidate order is deterministic, so this stays reproducible.
          continue;
        }
        String body = Files.readString(abs, StandardCharsets.UTF_8);
        totalChars += body.length();
        out.put(rel.toString().replace('\\', '/'), body);
      } catch (IOException | RuntimeException e) {
        // Binary or unreadable file — not a UI surface. Skip without failing discovery.
        log.trace("preview-render skipped unreadable file {}", rel, e);
      }
    }
    return out;
  }

  /** Walks the workspace, pruning skipped trees. Returns null when the workspace cannot be read. */
  private List<Path> collectCandidatePaths(Path workspace) {
    List<Path> candidates = new ArrayList<>();
    try {
      Files.walkFileTree(
          workspace,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              // Never skip the root: a workspace named "build" still has screens under it.
              if (dir.equals(workspace)) {
                return FileVisitResult.CONTINUE;
              }
              Path name = dir.getFileName();
              if (name != null && SKIP_DIRS.contains(name.toString())) {
                return FileVisitResult.SKIP_SUBTREE;
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              if (!attrs.isRegularFile() || attrs.size() > MAX_FILE_BYTES) {
                return FileVisitResult.CONTINUE;
              }
              Path rel = workspace.relativize(file);
              if (isInterestingExtension(rel.toString())) {
                candidates.add(rel);
              }
              // Bounds memory on a pathological tree. Well above any authored-source repo, so the
              // sort below still sees the whole candidate set in practice.
              return candidates.size() >= MAX_CANDIDATE_PATHS
                  ? FileVisitResult.TERMINATE
                  : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
              log.trace("preview-render could not stat {}", file, e);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      log.warn("preview-render workspace walk failed for {}", workspace, e);
      return null;
    }
    return candidates;
  }

  private boolean isInterestingExtension(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    return lower.endsWith(".tsx")
        || lower.endsWith(".jsx")
        || lower.endsWith(".ts")
        || lower.endsWith(".js")
        || lower.endsWith(".vue")
        || lower.endsWith(".svelte")
        || lower.endsWith(".html")
        || lower.endsWith(".htm")
        || lower.endsWith(".css")
        || lower.endsWith(".swift")
        || lower.endsWith(".kt")
        || lower.endsWith(".java")
        || lower.endsWith(".xml")
        || lower.equals("package.json");
  }
}
