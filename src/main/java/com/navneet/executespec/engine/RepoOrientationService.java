package com.navneet.executespec.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.navneet.executespec.smoketest.BuildCommandResolver;
import com.navneet.executespec.smoketest.TestCommandResolver;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Harness-resolution task 11 (flag {@code worker.localization.enabled}, V255, default OFF) — the
 * DETERMINISTIC repo-orientation layer: a bounded, zero-LLM-cost warm-start block injected into
 * the worker's first user message so localization doesn't have to be re-derived from a cold
 * prompt one readFile at a time (assessment Gap 5 / deep-dive 5).
 *
 * <p>Contents: a depth-capped directory sketch, literal-match hits for identifiers extracted from
 * the task text (CamelCase / snake_case / dotted / quoted symbols), and the resolver-detected
 * build+test commands. Everything is ADVISORY — the block says so explicitly — and everything is
 * fail-open: any error returns "" and costs the run nothing.
 *
 * <p>The LIGHTWEIGHT-tier LLM refinement (read-only subagent producing symbol/call-site maps) is
 * the logged follow-up; this deterministic layer alone removes the cold-start for most tasks.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RepoOrientationService {

  static final int MAX_BLOCK_CHARS = 3_500;
  static final int MAX_IDENTIFIERS = 8;
  static final int MAX_HITS_PER_IDENTIFIER = 5;
  static final int MAX_FILES_SCANNED = 4_000;
  static final int MAX_TREE_ENTRIES = 40;
  static final long MAX_FILE_BYTES = 512 * 1024;

  private static final Set<String> SKIP_DIRS =
      Set.of(
          ".git", "node_modules", "target", "build", "dist", ".next", ".venv", "venv",
          "__pycache__", ".gradle", ".idea", ".vscode", "vendor", ".agent-context");

  /** CamelCase / snake_case / dotted / path-like identifiers worth grepping for. */
  private static final Pattern IDENTIFIER =
      Pattern.compile(
          "\\b([A-Z][a-z0-9]+(?:[A-Z][a-z0-9]+)+|[a-z][a-z0-9]*(?:_[a-z0-9]+)+"
              + "|[A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z][A-Za-z0-9]*)+|`[^`\\s]{3,60}`)\\b");

  private static final Set<String> STOPWORDS =
      Set.of(
          "должен", "should_be", "for_example", "e.g", "i.e", "node_modules", "package.json",
          "requirements.txt", "pom.xml", "go.mod", "setup.py");

  private final BuildCommandResolver buildCommandResolver;
  private final TestCommandResolver testCommandResolver;

  /**
   * Build the orientation block for a task in an existing repo. Empty string when the workspace
   * is missing/empty or anything fails (fail-open, never throws).
   */
  public String orient(Path workspaceRoot, String taskText) {
    try {
      if (workspaceRoot == null || taskText == null || !Files.isDirectory(workspaceRoot)) {
        return "";
      }
      List<Path> files = listSourceFiles(workspaceRoot);
      if (files.isEmpty()) return ""; // greenfield — nothing to orient on

      StringBuilder sb = new StringBuilder();
      sb.append("# REPO ORIENTATION (advisory — verify before relying on it)\n\n");

      String tree = directorySketch(workspaceRoot);
      if (!tree.isBlank()) {
        sb.append("Top-level layout:\n").append(tree).append("\n");
      }

      List<String> identifiers = extractIdentifiers(taskText);
      Map<String, List<String>> hits = grepIdentifiers(workspaceRoot, files, identifiers);
      if (!hits.isEmpty()) {
        sb.append("Task-identifier matches (candidate starting points, NOT a complete list):\n");
        for (Map.Entry<String, List<String>> e : hits.entrySet()) {
          sb.append("- `").append(e.getKey()).append("` → ");
          sb.append(String.join(", ", e.getValue()));
          sb.append("\n");
        }
        sb.append("\n");
      }

      appendCommands(sb, workspaceRoot);

      if (sb.length() > MAX_BLOCK_CHARS) {
        sb.setLength(MAX_BLOCK_CHARS);
        sb.append("\n...[orientation truncated]");
      }
      return sb.toString();
    } catch (Exception e) {
      log.debug("RepoOrientationService failed (fail-open): {}", e.getMessage());
      return "";
    }
  }

  private void appendCommands(StringBuilder sb, Path root) {
    try {
      Optional<TestCommandResolver.TestCommand> test = testCommandResolver.resolve(root);
      test.ifPresent(t -> sb.append("Detected test command: `").append(t.command()).append("`\n"));
      if (buildCommandResolver != null) {
        var build = buildCommandResolver.resolve(root, null);
        if (build != null) {
          sb.append("Detected build command: `").append(build.command()).append("`\n");
        }
      }
    } catch (Exception ignored) {
      // advisory only
    }
  }

  private static List<Path> listSourceFiles(Path root) {
    List<Path> out = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(root, 6)) {
      walk.filter(Files::isRegularFile)
          .filter(p -> !isSkipped(root, p))
          .limit(MAX_FILES_SCANNED)
          .forEach(out::add);
    } catch (IOException e) {
      return List.of();
    }
    return out;
  }

  private static boolean isSkipped(Path root, Path p) {
    Path rel = root.relativize(p);
    for (Path part : rel) {
      String name = part.toString();
      if (SKIP_DIRS.contains(name) || (name.startsWith(".") && !name.equals("."))) return true;
    }
    return false;
  }

  /** Depth-1 sketch: top-level dirs with file counts, top-level files. */
  private String directorySketch(Path root) {
    StringBuilder sb = new StringBuilder();
    try (Stream<Path> list = Files.list(root)) {
      List<Path> entries =
          list.filter(p -> !isSkipped(root, p) || Files.isDirectory(p))
              .filter(p -> !SKIP_DIRS.contains(p.getFileName().toString()))
              .filter(p -> !p.getFileName().toString().startsWith("."))
              .sorted(
                  Comparator.comparing((Path p) -> !Files.isDirectory(p))
                      .thenComparing(p -> p.getFileName().toString()))
              .limit(MAX_TREE_ENTRIES)
              .toList();
      for (Path e : entries) {
        if (Files.isDirectory(e)) {
          long count = 0;
          try (Stream<Path> sub = Files.walk(e, 3)) {
            count = sub.filter(Files::isRegularFile).limit(1000).count();
          } catch (IOException ignored) {
            // count stays 0 — still list the dir
          }
          sb.append("- ").append(e.getFileName()).append("/ (~").append(count).append(" files)\n");
        } else {
          sb.append("- ").append(e.getFileName()).append("\n");
        }
      }
    } catch (IOException e) {
      return "";
    }
    return sb.toString();
  }

  static List<String> extractIdentifiers(String taskText) {
    LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
    Matcher m = IDENTIFIER.matcher(taskText);
    while (m.find()) {
      String id = m.group(1).replace("`", "").trim();
      if (id.length() < 4 || id.length() > 60) continue;
      if (STOPWORDS.contains(id.toLowerCase(java.util.Locale.ROOT))) continue;
      counts.merge(id, 1, Integer::sum);
    }
    return counts.entrySet().stream()
        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
        .limit(MAX_IDENTIFIERS)
        .map(Map.Entry::getKey)
        .toList();
  }

  private static Map<String, List<String>> grepIdentifiers(
      Path root, List<Path> files, List<String> identifiers) {
    Map<String, List<String>> hits = new LinkedHashMap<>();
    if (identifiers.isEmpty()) return hits;
    Set<String> remaining = new LinkedHashSet<>(identifiers);
    for (Path f : files) {
      if (remaining.isEmpty()) break;
      String content;
      try {
        if (Files.size(f) > MAX_FILE_BYTES) continue;
        content = Files.readString(f);
      } catch (Exception e) {
        continue; // binary / unreadable — skip
      }
      for (String id : identifiers) {
        if (!content.contains(id)) continue;
        List<String> list = hits.computeIfAbsent(id, k -> new ArrayList<>());
        if (list.size() < MAX_HITS_PER_IDENTIFIER) {
          list.add(root.relativize(f).toString());
          if (list.size() == MAX_HITS_PER_IDENTIFIER) remaining.remove(id);
        }
      }
    }
    return hits;
  }
}
