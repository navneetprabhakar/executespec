package com.navneet.executespec.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.springframework.stereotype.Service;

/**
 * Deterministic, workspace-confined repository search contract. This is the typed seam shared by
 * worker tools, project chat, local-hands clients, and future optional explorers (for example
 * FastContext). It deliberately uses a bounded Java implementation first; an rg/tree-sitter adapter
 * can implement the same request/result contract without changing callers.
 */
@Service
public class RepositorySearchService {

  private static final Set<String> IGNORED_DIRS =
      Set.of(
          ".git",
          "node_modules",
          "target",
          "build",
          "dist",
          ".next",
          ".venv",
          "__pycache__",
          ".gradle",
          ".idea",
          ".vscode",
          "worktrees");
  private static final int MAX_HITS = 1000;
  private static final int MAX_PREVIEW = 240;
  /** Small, process-local index. It is deliberately bounded because workspaces are untrusted. */
  private static final int MAX_INDEXED_WORKSPACES = 4;
  private final Map<Path, CachedWorkspace> workspaceIndex =
      new LinkedHashMap<>(MAX_INDEXED_WORKSPACES, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, CachedWorkspace> eldest) {
          return size() > MAX_INDEXED_WORKSPACES;
        }
      };

  public SearchResult search(Path workspace, SearchRequest request) {
    if (workspace == null
        || request == null
        || request.query() == null
        || request.query().isBlank()) {
      throw new IllegalArgumentException("workspace and non-blank query are required");
    }
    try {
      Path root = workspace.toRealPath();
      Path searchRoot =
          request.relativeRoot() == null || request.relativeRoot().isBlank()
              ? root
              : root.resolve(request.relativeRoot()).normalize().toRealPath();
      if (!searchRoot.startsWith(root))
        throw new SecurityException("search root escapes workspace");
      String repoSha = gitSha(root);
      List<Path> files = indexedFiles(root, searchRoot);
      String fingerprint = fingerprint(files);
      Pattern pattern = compile(request);
      List<SearchHit> hits = new ArrayList<>();
      for (Path file : files) {
        if (request.fileGlob() != null
            && !file.getFileName().toString().matches(globRegex(request.fileGlob()))) continue;
        List<String> lines = indexedLines(root, file);
        if (lines == null) continue;
        for (int i = 0; i < lines.size(); i++) {
          String line = lines.get(i);
          if (!pattern.matcher(line).find()) continue;
          Path relative = root.relativize(file);
          hits.add(
              new SearchHit(
                  relative.toString(),
                  i + 1,
                  1,
                  line.substring(0, Math.min(MAX_PREVIEW, line.length())),
                  "TEXT",
                  "CURRENT",
                  estimate(line)));
          if (hits.size() >= MAX_HITS) break;
        }
        if (hits.size() >= MAX_HITS) break;
      }
      hits.sort(Comparator.comparing(SearchHit::path).thenComparingInt(SearchHit::line));
      int from = Math.min(request.offset(), hits.size());
      int to = Math.min(from + request.limit(), hits.size());
      return new SearchResult(
          repoSha,
          fingerprint,
          sha256(request.query()),
          hits.subList(from, to),
          hits.size(),
          to < hits.size(),
          to,
          "java-deterministic");
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException("invalid search regex: " + e.getDescription(), e);
    } catch (IOException e) {
      throw new IllegalStateException("repository search failed", e);
    }
  }

  /**
   * Returns a stable inventory while avoiding a full directory walk for every tool turn. The
   * metadata check keeps the cache safe for live worktrees: a changed file is re-read before it
   * can contribute a hit. This is the local-index seam that an optional FastContext/tree-sitter
   * backend can replace later without changing the search contract or callers.
   */
  private List<Path> indexedFiles(Path root, Path searchRoot) throws IOException {
    synchronized (workspaceIndex) {
      CachedWorkspace cached = workspaceIndex.get(root);
      // Keep inventory discovery authoritative on every call (new/deleted files must be visible),
      // while reusing decoded file lines below. This avoids stale-search bugs and still removes the
      // expensive readAllLines allocation that dominates repeated agent search turns.
      List<Path> currentFiles = inventory(root, root);
      if (cached == null) {
        cached = new CachedWorkspace(root, currentFiles);
        workspaceIndex.put(root, cached);
      } else cached.files = List.copyOf(currentFiles);
      return cached.files.stream()
          .filter(path -> path.startsWith(searchRoot))
          .toList();
    }
  }

  private List<String> indexedLines(Path root, Path file) {
    synchronized (workspaceIndex) {
      CachedWorkspace cached = workspaceIndex.get(root);
      if (cached == null) return null;
      return cached.lines(file);
    }
  }

  private static final class CachedWorkspace {
    private final Path root;
    private List<Path> files;
    private final Map<Path, FileSnapshot> snapshots = new LinkedHashMap<>();

    private CachedWorkspace(Path root, List<Path> files) {
      this.root = root;
      this.files = List.copyOf(files);
    }

    private List<String> lines(Path file) {
      try {
        long size = Files.size(file);
        long modified = Files.getLastModifiedTime(file).toMillis();
        FileSnapshot previous = snapshots.get(file);
        if (previous != null && previous.size == size && previous.modified == modified) {
          return previous.lines;
        }
        List<String> current = Files.readAllLines(file);
        snapshots.put(file, new FileSnapshot(size, modified, current));
        return current;
      } catch (IOException | OutOfMemoryError e) {
        snapshots.remove(file);
        return null;
      }
    }
  }

  private record FileSnapshot(long size, long modified, List<String> lines) {}

  private static Pattern compile(SearchRequest request) {
    return Pattern.compile(
        request.mode() == SearchMode.EXACT ? Pattern.quote(request.query()) : request.query(),
        request.caseSensitive() ? 0 : Pattern.CASE_INSENSITIVE);
  }

  private static List<Path> inventory(Path root, Path searchRoot) throws IOException {
    List<Path> files = new ArrayList<>();
    Files.walkFileTree(
        searchRoot,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            return dir.equals(searchRoot) || !IGNORED_DIRS.contains(dir.getFileName().toString())
                ? FileVisitResult.CONTINUE
                : FileVisitResult.SKIP_SUBTREE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (attrs.isRegularFile() && !isBinaryName(file)) files.add(file);
            return FileVisitResult.CONTINUE;
          }
        });
    files.sort(Comparator.comparing(path -> root.relativize(path).toString()));
    return files;
  }

  private static boolean isBinaryName(Path path) {
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.endsWith(".class")
        || name.endsWith(".jar")
        || name.endsWith(".zip")
        || name.endsWith(".png")
        || name.endsWith(".jpg")
        || name.endsWith(".gif")
        || name.endsWith(".pdf")
        || name.endsWith(".gz");
  }

  private static String globRegex(String glob) {
    StringBuilder out = new StringBuilder("^");
    for (char c : glob.toCharArray())
      out.append(c == '*' ? ".*" : c == '?' ? '.' : "\\.^$|()[]{}+".indexOf(c) >= 0 ? "\\" + c : c);
    return out.append('$').toString();
  }

  private static int estimate(String value) {
    return Math.max(1, value.length() / 4);
  }

  private static String gitSha(Path root) {
    try {
      Process process =
          new ProcessBuilder("git", "-C", root.toString(), "rev-parse", "HEAD")
              .redirectErrorStream(true)
              .start();
      String value =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      return process.waitFor() == 0 && !value.isBlank() ? value : "WORKTREE_ONLY";
    } catch (Exception ignored) {
      return "WORKTREE_ONLY";
    }
  }

  private static String fingerprint(List<Path> files) throws IOException {
    MessageDigest digest = digest();
    for (Path file : files) {
      digest.update(file.toString().getBytes(StandardCharsets.UTF_8));
      digest.update(Long.toString(Files.size(file)).getBytes(StandardCharsets.UTF_8));
      digest.update(
          Long.toString(Files.getLastModifiedTime(file).toMillis())
              .getBytes(StandardCharsets.UTF_8));
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String sha256(String value) {
    MessageDigest d = digest();
    return HexFormat.of().formatHex(d.digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  public enum SearchMode {
    EXACT,
    REGEX
  }

  public record SearchRequest(
      String query,
      SearchMode mode,
      boolean caseSensitive,
      String relativeRoot,
      String fileGlob,
      int limit,
      int offset) {
    public SearchRequest {
      mode = mode == null ? SearchMode.EXACT : mode;
      limit = Math.max(1, Math.min(200, limit <= 0 ? 50 : limit));
      offset = Math.max(0, offset);
    }
  }

  public record SearchHit(
      String path,
      int line,
      int column,
      String preview,
      String matchType,
      String freshness,
      int tokenEstimate) {}

  public record SearchResult(
      String repositorySha,
      String inventoryFingerprint,
      String queryFingerprint,
      List<SearchHit> hits,
      int totalMatches,
      boolean hasMore,
      int nextOffset,
      String engine) {}
}
