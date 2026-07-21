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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Deterministically finds the renderable screens in a run workspace.
 *
 * <p>No model participates in discovery. That matters for two reasons: the render stays
 * reproducible for the same workspace, and the token budget is known before any provider call is
 * made. The agent is handed a fixed, capped set of surfaces and cannot go looking for more.
 */
@Slf4j
@Component
public class PreviewSurfaceDiscovery {

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

  /** Relative-import specifiers, e.g. {@code import Foo from './Foo'}. */
  private static final Pattern RELATIVE_IMPORT =
      Pattern.compile("(?:from|import)\\s+['\"](\\.[^'\"]+)['\"]");

  /** Theme and design-token files worth injecting into every render of a project. */
  private static final List<String> TOKEN_FILE_HINTS =
      List.of(
          "tailwind.config",
          "theme.ts",
          "theme.tsx",
          "theme.js",
          "tokens.ts",
          "tokens.css",
          "colors.ts",
          "globals.css",
          "index.css",
          "app.css",
          "Color.kt",
          "Theme.kt",
          "Type.kt",
          "colors.xml",
          "themes.xml",
          "styles.xml",
          "Colors.swift",
          "Theme.swift");

  /** Path segments that strongly suggest a top-level screen rather than a leaf component. */
  private static final List<String> SCREEN_DIR_HINTS =
      List.of("screens", "pages", "views", "routes", "app", "activities", "fragments");

  /**
   * Finds renderable surfaces, most screen-like first.
   *
   * @param workspace run workspace root
   * @param changedProductPaths workspace-relative paths the run actually changed; surfaces among
   *     them are ranked first so the preview shows the run's own work. May be empty.
   * @param maxSurfaces hard cap on returned surfaces
   * @param characterBudget hard cap on total prompt characters across all returned surfaces
   */
  public List<PreviewSurface> discover(
      Path workspace, Set<String> changedProductPaths, int maxSurfaces, int characterBudget) {

    if (workspace == null || !Files.isDirectory(workspace)) {
      log.debug("preview-render discovery skipped, workspace absent: {}", workspace);
      return List.of();
    }

    Map<String, String> files = readCandidateFiles(workspace);
    if (files.isEmpty()) {
      return List.of();
    }

    boolean reactNativeProject = looksLikeReactNative(files);
    Map<String, String> tokenFiles = collectTokenFiles(files);

    List<Scored> scored = new ArrayList<>();
    for (Map.Entry<String, String> entry : files.entrySet()) {
      String path = entry.getKey();
      String content = entry.getValue();
      PreviewRenderTarget target = classify(path, content, reactNativeProject);
      if (target == null) {
        continue;
      }
      int score = screenScore(path, content, target);
      if (score <= 0) {
        continue;
      }
      if (changedProductPaths != null && changedProductPaths.contains(path)) {
        // The run's own output is what the operator asked to see.
        score += 100;
      }
      scored.add(new Scored(path, content, target, score));
    }

    scored.sort(
        Comparator.comparingInt((Scored s) -> s.score).reversed().thenComparing(s -> s.path));

    List<PreviewSurface> out = new ArrayList<>();
    Set<String> usedIds = new HashSet<>();
    int spent = 0;

    for (Scored s : scored) {
      if (out.size() >= maxSurfaces) {
        break;
      }
      List<PreviewSurface.SupportingSource> supporting =
          gatherSupporting(s.path, s.content, files, tokenFiles);

      PreviewSurface surface =
          new PreviewSurface(
              uniqueId(s.path, usedIds),
              s.path,
              displayName(s.path),
              s.target,
              s.content,
              supporting,
              s.score);

      int cost = surface.characterCost();
      if (spent + cost > characterBudget) {
        // Drop supporting context and retry at the reduced cost rather than losing the screen.
        surface =
            new PreviewSurface(
                surface.id(),
                surface.path(),
                surface.displayName(),
                surface.target(),
                surface.source(),
                List.of(),
                surface.score());
        cost = surface.characterCost();
        if (spent + cost > characterBudget) {
          continue;
        }
      }
      spent += cost;
      out.add(surface);
    }

    log.info(
        "preview-render discovery workspace={} candidates={} selected={} chars={}",
        workspace.getFileName(),
        scored.size(),
        out.size(),
        spent);
    return out;
  }

  // ---------------------------------------------------------------- classification

  /** Returns the render target for a file, or null when the file is not a renderable surface. */
  private PreviewRenderTarget classify(String path, String content, boolean reactNativeProject) {
    String lower = path.toLowerCase(Locale.ROOT);

    if (lower.endsWith(".swift")) {
      boolean isView =
          content.contains(": View")
              || content.contains("UIViewController")
              || content.contains("SwiftUI");
      return isView ? PreviewRenderTarget.IOS_NATIVE : null;
    }
    if (lower.endsWith(".kt") || lower.endsWith(".java")) {
      // Only Compose screens are renderable; ordinary Kotlin/Java has no layout to read.
      return content.contains("@Composable") ? PreviewRenderTarget.ANDROID_NATIVE : null;
    }
    if (lower.endsWith(".xml")) {
      boolean androidLayout = lower.contains("/res/layout") && content.contains("android:");
      return androidLayout ? PreviewRenderTarget.ANDROID_NATIVE : null;
    }
    if (lower.endsWith(".vue") || lower.endsWith(".svelte")) {
      return PreviewRenderTarget.WEB;
    }
    if (lower.endsWith(".html") || lower.endsWith(".htm")) {
      return PreviewRenderTarget.WEB;
    }
    if (lower.endsWith(".tsx") || lower.endsWith(".jsx")) {
      boolean importsReactNative =
          content.contains("from 'react-native'")
              || content.contains("from \"react-native\"")
              || content.contains("from 'expo-")
              || content.contains("from \"expo-");
      if (importsReactNative || reactNativeProject) {
        return PreviewRenderTarget.REACT_NATIVE;
      }
      return PreviewRenderTarget.WEB;
    }
    return null;
  }

  /**
   * Scores how likely a file is a whole screen. Leaf components and test files score zero and are
   * never rendered on their own.
   */
  private int screenScore(String path, String content, PreviewRenderTarget target) {
    String lower = path.toLowerCase(Locale.ROOT);
    String fileName = fileName(lower);

    if (lower.contains("/test/")
        || lower.contains("/__tests__/")
        || fileName.contains(".test.")
        || fileName.contains(".spec.")
        || fileName.contains(".stories.")
        || fileName.endsWith(".d.ts")) {
      return 0;
    }

    int score = 10;

    for (String hint : SCREEN_DIR_HINTS) {
      if (lower.contains("/" + hint + "/")) {
        score += 40;
        break;
      }
    }

    if (fileName.contains("screen")
        || fileName.contains("page")
        || fileName.contains("view")
        || fileName.contains("activity")) {
      score += 35;
    }

    // Framework route conventions: Next.js app/pages router, SvelteKit, Expo Router.
    if (fileName.equals("page.tsx")
        || fileName.equals("page.jsx")
        || fileName.equals("+page.svelte")
        || fileName.equals("index.html")
        || fileName.equals("layout.tsx")) {
      score += 45;
    }

    if (target == PreviewRenderTarget.WEB || target == PreviewRenderTarget.REACT_NATIVE) {
      if (content.contains("export default")) {
        score += 20;
      }
      // A screen renders markup; a hook or util file does not.
      if (!content.contains("return (") && !content.contains("<")) {
        return 0;
      }
    }

    if (target == PreviewRenderTarget.ANDROID_NATIVE && content.contains("@Preview")) {
      score += 25;
    }
    if (target == PreviewRenderTarget.IOS_NATIVE && content.contains("PreviewProvider")) {
      score += 25;
    }

    // Deeply nested files are usually building blocks, not destinations.
    int depth = path.split("/").length;
    if (depth > 6) {
      score -= (depth - 6) * 5;
    }

    return Math.max(score, 0);
  }

  // ---------------------------------------------------------------- context gathering

  /**
   * Pulls in the local components a screen imports plus the project's design tokens, so the agent
   * can lay out the screen without inventing a visual language that contradicts the codebase.
   */
  private List<PreviewSurface.SupportingSource> gatherSupporting(
      String screenPath,
      String content,
      Map<String, String> files,
      Map<String, String> tokenFiles) {

    List<PreviewSurface.SupportingSource> out = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();

    Matcher m = RELATIVE_IMPORT.matcher(content);
    while (m.find() && out.size() < 8) {
      String resolved = resolveRelativeImport(screenPath, m.group(1), files.keySet());
      if (resolved != null && seen.add(resolved)) {
        out.add(new PreviewSurface.SupportingSource(resolved, files.get(resolved)));
      }
    }

    for (Map.Entry<String, String> token : tokenFiles.entrySet()) {
      if (out.size() >= 12) {
        break;
      }
      if (seen.add(token.getKey())) {
        out.add(new PreviewSurface.SupportingSource(token.getKey(), token.getValue()));
      }
    }
    return out;
  }

  /** Resolves {@code ./Foo} against the importing file, trying the usual extension order. */
  private String resolveRelativeImport(String fromPath, String spec, Set<String> known) {
    int slash = fromPath.lastIndexOf('/');
    String dir = slash < 0 ? "" : fromPath.substring(0, slash);

    String joined = dir.isEmpty() ? spec : dir + "/" + spec;
    String normalized = normalize(joined);
    if (normalized == null) {
      return null;
    }

    if (known.contains(normalized)) {
      return normalized;
    }
    for (String ext : List.of(".tsx", ".ts", ".jsx", ".js", ".vue", ".svelte", ".css")) {
      if (known.contains(normalized + ext)) {
        return normalized + ext;
      }
    }
    for (String ext : List.of("/index.tsx", "/index.ts", "/index.jsx", "/index.js")) {
      if (known.contains(normalized + ext)) {
        return normalized + ext;
      }
    }
    return null;
  }

  /** Collapses {@code .} and {@code ..} segments. Returns null if the path escapes the root. */
  private String normalize(String raw) {
    List<String> parts = new ArrayList<>();
    for (String segment : raw.split("/")) {
      if (segment.isEmpty() || segment.equals(".")) {
        continue;
      }
      if (segment.equals("..")) {
        if (parts.isEmpty()) {
          return null;
        }
        parts.remove(parts.size() - 1);
        continue;
      }
      parts.add(segment);
    }
    return String.join("/", parts);
  }

  private Map<String, String> collectTokenFiles(Map<String, String> files) {
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : files.entrySet()) {
      String name = fileName(entry.getKey()).toLowerCase(Locale.ROOT);
      for (String hint : TOKEN_FILE_HINTS) {
        String h = hint.toLowerCase(Locale.ROOT);
        // Extensionless hints such as "tailwind.config" must also match tailwind.config.js / .ts /
        // .cjs.
        if (name.equals(h) || name.startsWith(h + ".")) {
          out.put(entry.getKey(), entry.getValue());
          break;
        }
      }
      if (out.size() >= 4) {
        break;
      }
    }
    return out;
  }

  // ---------------------------------------------------------------- workspace reading

  /**
   * Collects the readable candidate screens, in a stable order.
   *
   * <p>Two properties this has to get right, because the whole cost bound depends on them:
   *
   * <ol>
   *   <li>Skipped trees are pruned during the walk, not filtered afterwards. {@code node_modules}
   *       alone routinely holds more files than the cap allows, so a cap applied to the raw file
   *       stream would be spent entirely on directories that were never candidates — and an
   *       ordinary JS repo would report no screens at all.
   *   <li>The surviving set is sorted by relative path before anything is read. Directory iteration
   *       order is filesystem-dependent and unstable across machines and clones, so ordering by it
   *       would make both the selection and its token cost irreproducible.
   * </ol>
   */
  private Map<String, String> readCandidateFiles(Path workspace) {
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

  private boolean looksLikeReactNative(Map<String, String> files) {
    String pkg = files.get("package.json");
    if (pkg == null) {
      for (Map.Entry<String, String> e : files.entrySet()) {
        if (e.getKey().endsWith("package.json")) {
          pkg = e.getValue();
          break;
        }
      }
    }
    if (pkg == null) {
      return false;
    }
    return pkg.contains("\"react-native\"") || pkg.contains("\"expo\"");
  }

  // ---------------------------------------------------------------- naming

  private String fileName(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }

  private String uniqueId(String path, Set<String> used) {
    String base =
        path.replaceAll("\\.[^./]+$", "").replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "");
    if (base.isEmpty()) {
      base = "surface";
    }
    base = base.toLowerCase(Locale.ROOT);
    if (base.length() > 80) {
      base = base.substring(base.length() - 80);
    }
    String candidate = base;
    int n = 2;
    while (!used.add(candidate)) {
      candidate = base + "-" + n++;
    }
    return candidate;
  }

  private String displayName(String path) {
    String name = fileName(path).replaceAll("\\.[^.]+$", "");
    if (name.equalsIgnoreCase("page")
        || name.equalsIgnoreCase("index")
        || name.equalsIgnoreCase("layout")) {
      // These filenames are route markers; the parent directory carries the meaning.
      int slash = path.lastIndexOf('/');
      if (slash > 0) {
        String parent = path.substring(0, slash);
        int parentSlash = parent.lastIndexOf('/');
        String parentName = parentSlash < 0 ? parent : parent.substring(parentSlash + 1);
        if (!parentName.isBlank()) {
          name = parentName;
        }
      }
    }
    String spaced = name.replaceAll("[-_]+", " ").replaceAll("(?<=[a-z])(?=[A-Z])", " ").trim();
    if (spaced.isEmpty()) {
      return path;
    }
    return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
  }

  private record Scored(String path, String content, PreviewRenderTarget target, int score) {}
}
