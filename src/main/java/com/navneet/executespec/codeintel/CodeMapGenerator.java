package com.navneet.executespec.codeintel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.repo.GitHubRepoService;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Pilot R3 — produces a markdown "code map" for one repo by scanning its file listing (via GitHub
 * Trees API) and a few key files (routes, entities, components). No shallow clone — keeps
 * generation cheap enough to be a synchronous step on the dashboard regenerate button.
 *
 * <p><b>2026-05-10</b> — codemap was a single "Files: N" line plus a top-level dir list, which gave
 * the planner / chat agent file-tree shape but no signal about what each file <i>does</i>. Added a
 * per-file function map so downstream agents can see "user.service.ts exports getUser, listUsers,
 * createUser" without paying the LLM-read cost. Function extraction is regex-only (no AST), bounded
 * to ~40 source files and 30 functions per file.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CodeMapGenerator {

  private final GitHubRepoService github;

  private static final Pattern SPRING_ROUTE =
      Pattern.compile(
          "@(?:Get|Post|Put|Delete|Patch|Request)Mapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");
  private static final Pattern EXPRESS_ROUTE =
      Pattern.compile(
          "(?:app|router)\\.(?:get|post|put|delete|patch)\\s*\\(\\s*['\"]([^'\"]+)['\"]");
  private static final Pattern FASTAPI_ROUTE =
      Pattern.compile(
          "@(?:app|router)\\.(?:get|post|put|delete|patch)\\s*\\(\\s*['\"]([^'\"]+)['\"]");

  // Function/method extractors — keyed by language family. Group(1) is always the function name.
  // Patterns are intentionally conservative: skim the obvious "fn name(" / "def name(" /
  // "class Name" shapes without trying to parse signatures. False positives on macros / unusual
  // formatting are acceptable; false negatives just trim the listing.
  private static final Pattern JAVA_METHOD =
      Pattern.compile(
          "(?m)^\\s*(?:public|private|protected|static|final|synchronized|abstract|@\\w+(?:\\([^)]*\\))?\\s+)+"
              + "(?:<[^>]+>\\s+)?[\\w<>\\[\\]?,.\\s]+?\\s+(\\w+)\\s*\\([^)]*\\)\\s*(?:throws\\s+[^{]+)?\\{");
  private static final Pattern JAVA_CLASS =
      Pattern.compile(
          "(?m)^\\s*(?:public|private|protected|static|final|abstract|sealed)*\\s*"
              + "(?:class|interface|record|enum)\\s+(\\w+)");
  private static final Pattern PYTHON_DEF = Pattern.compile("(?m)^\\s*(?:async\\s+)?def\\s+(\\w+)");
  private static final Pattern PYTHON_CLASS = Pattern.compile("(?m)^\\s*class\\s+(\\w+)");
  private static final Pattern TS_FUNCTION =
      Pattern.compile(
          "(?m)^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+(\\w+)|"
              + "(?m)^\\s*(?:export\\s+)?const\\s+(\\w+)\\s*[:=]\\s*(?:async\\s*)?\\([^)]*\\)\\s*(?::\\s*[^=]+)?\\s*=>");
  private static final Pattern TS_CLASS =
      Pattern.compile("(?m)^\\s*(?:export\\s+)?(?:abstract\\s+)?class\\s+(\\w+)");
  private static final Pattern TS_METHOD =
      Pattern.compile(
          "(?m)^\\s{2,}(?:public|private|protected|static|async)?\\s*(\\w+)\\s*\\([^)]*\\)"
              + "(?:\\s*:\\s*[^{]+)?\\s*\\{");
  private static final Pattern GO_FUNC =
      Pattern.compile("(?m)^func\\s+(?:\\([^)]*\\)\\s+)?(\\w+)\\s*\\(");
  private static final Pattern RUST_FN =
      Pattern.compile("(?m)^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:async\\s+)?fn\\s+(\\w+)\\s*[(<]");
  private static final Pattern RUBY_DEF = Pattern.compile("(?m)^\\s*def\\s+([\\w?!=]+)");
  private static final Pattern KOTLIN_FUN =
      Pattern.compile("(?m)^\\s*(?:public|private|internal|fun)\\s*(?:fun\\s+)?(\\w+)\\s*\\(");

  /** Paths we DO want to scan for functions. Hand-tuned to match real-world layout patterns. */
  private static final Pattern INTERESTING_PATH =
      Pattern.compile(
          "(?:^|/)(?:src|app|lib|core|services?|controllers?|handlers?|routes?|api|"
              + "models?|entities?|domain|repository|repositories|usecases?|use_cases|"
              + "internal|cmd|pkg|crates|packages)/.+",
          Pattern.CASE_INSENSITIVE);

  /** Paths we skip even if they match {@link #INTERESTING_PATH} — tests, generated, vendor. */
  private static final Pattern SKIP_PATH =
      Pattern.compile(
          "(?:^|/)(?:node_modules|dist|build|target|out|bin|coverage|vendor|\\.git|"
              + "__tests__|__mocks__|__snapshots__|tests?|test|spec|specs|fixtures?|"
              + "generated|gen|\\.next|\\.nuxt|\\.svelte-kit)(?:/|$)|"
              + "\\.(?:test|spec|d|min|bundle|generated)\\.(?:[a-z]+)$",
          Pattern.CASE_INSENSITIVE);

  public record Result(String markdown, String sha) {}

  public Result generateWithSha(String repoFullName, String defaultBranch, String pat) {
    String sha = github.getBranchHeadSha("https://github.com/" + repoFullName, defaultBranch, pat);
    return new Result(generate(repoFullName, defaultBranch, pat), sha);
  }

  public String generate(String repoFullName, String pat) {
    return generate(repoFullName, null, pat);
  }

  public String generate(String repoFullName, String defaultBranch, String pat) {
    List<String> files;
    try {
      files = github.listRepoFiles("https://github.com/" + repoFullName, defaultBranch, pat);
    } catch (Exception e) {
      log.warn("CodeMapGenerator list-files failed for {}: {}", repoFullName, e.getMessage());
      files = List.of();
    }

    StringBuilder md = new StringBuilder();
    md.append("# ").append(repoFullName).append("\n\n");
    md.append("**Files**: ").append(files.size()).append("\n\n");

    // Surface major directories
    md.append("## Top-level layout\n\n");
    files.stream()
        .map(p -> p.contains("/") ? p.substring(0, p.indexOf('/')) : p)
        .distinct()
        .sorted()
        .limit(20)
        .forEach(d -> md.append("- ").append(d).append("\n"));
    md.append("\n");

    // Routes — scan a small set of likely files; full-text scan would be too expensive.
    List<String> routes = scanRoutes(repoFullName, defaultBranch, files, pat);
    if (!routes.isEmpty()) {
      md.append("## Routes\n\n");
      routes.stream().limit(40).forEach(r -> md.append("- `").append(r).append("`\n"));
      if (routes.size() > 40) md.append("- ... (+").append(routes.size() - 40).append(" more)\n");
      md.append("\n");
    }

    // Entities / models — best-effort by filename heuristic
    List<String> models =
        files.stream()
            .filter(
                p ->
                    p.matches(
                        ".*/(?:models?|entit(?:y|ies)|schemas?)/[^/]+\\.(?:java|py|ts|js|go|rs)$"))
            .limit(40)
            .toList();
    if (!models.isEmpty()) {
      md.append("## Models / entities\n\n");
      models.forEach(m -> md.append("- ").append(m).append("\n"));
      md.append("\n");
    }

    // 2026-05-10 — function map per file. Without it the codemap was just a header count + a
    // top-level dir list, which let the planner know "this repo exists" but nothing about what
    // any file does. Function names give the next layer of grain at zero LLM cost. Bounded:
    // we scan up to MAX_FUNCTION_FILES files and emit up to MAX_FUNCTIONS_PER_FILE per file
    // so the codemap stays prompt-friendly even on large repos.
    Map<String, List<String>> functions = scanFunctions(repoFullName, defaultBranch, files, pat);
    if (!functions.isEmpty()) {
      int total = functions.values().stream().mapToInt(List::size).sum();
      md.append("## Functions\n\n");
      md.append("Per-file map of public functions, methods, and types. Extracted from ")
          .append(functions.size())
          .append(" file")
          .append(functions.size() == 1 ? "" : "s")
          .append(" via regex (")
          .append(total)
          .append(" symbol")
          .append(total == 1 ? "" : "s")
          .append(" total).\n\n");
      functions.forEach(
          (path, fns) -> {
            md.append("### `").append(path).append("`\n\n");
            for (String fn : fns) md.append("- `").append(fn).append("`\n");
            md.append("\n");
          });
    }

    return md.toString();
  }

  private List<String> scanRoutes(
      String repoFullName, String branch, List<String> files, String pat) {
    List<String> out = new ArrayList<>();
    // Pull at most 12 plausible route files to keep API calls bounded.
    files.stream()
        .filter(
            p -> p.endsWith(".java") || p.endsWith(".py") || p.endsWith(".ts") || p.endsWith(".js"))
        .filter(p -> p.toLowerCase().matches(".*(controller|route|router|api).*"))
        .limit(12)
        .forEach(
            path -> {
              String content;
              try {
                content =
                    github.readRepoFile("https://github.com/" + repoFullName, path, branch, pat);
              } catch (Exception e) {
                return;
              }
              if (content == null || content.isBlank()) return;
              extract(SPRING_ROUTE, content, out);
              extract(EXPRESS_ROUTE, content, out);
              extract(FASTAPI_ROUTE, content, out);
            });
    return out.stream().distinct().sorted().toList();
  }

  private static void extract(Pattern p, String content, List<String> sink) {
    Matcher m = p.matcher(content);
    while (m.find()) sink.add(m.group(1));
  }

  /** Cap on files we open for function extraction. Bounded to keep API budget predictable. */
  static final int MAX_FUNCTION_FILES = 40;

  /** Cap on functions emitted per file. Higher and the codemap dominates the planner prompt. */
  static final int MAX_FUNCTIONS_PER_FILE = 30;

  /**
   * Scan a curated subset of source files for function/method/class names. Returns an ordered map
   * (insertion = path order) of {@code path → distinct function names}. Skips tests, generated
   * code, and vendor directories; prefers files under the conventional source layout (src/, app/,
   * lib/, services/, controllers/, etc.).
   */
  Map<String, List<String>> scanFunctions(
      String repoFullName, String branch, List<String> files, String pat) {
    LinkedHashMap<String, List<String>> out = new LinkedHashMap<>();

    List<String> candidates =
        files.stream()
            .filter(p -> isSourceFile(p))
            .filter(p -> !SKIP_PATH.matcher(p).find())
            .filter(p -> INTERESTING_PATH.matcher(p).find())
            .limit(MAX_FUNCTION_FILES)
            .toList();

    for (String path : candidates) {
      String content;
      try {
        content = github.readRepoFile("https://github.com/" + repoFullName, path, branch, pat);
      } catch (Exception e) {
        continue;
      }
      if (content == null || content.isBlank() || content.length() > 200_000) continue;

      List<String> symbols = extractSymbols(path, content);
      if (!symbols.isEmpty()) {
        out.put(path, symbols.stream().distinct().limit(MAX_FUNCTIONS_PER_FILE).toList());
      }
    }
    return out;
  }

  /**
   * Pull function / class / method names out of {@code content} based on the file extension. Each
   * entry in the returned list is a single identifier (e.g. {@code "getUser"} or {@code "class
   * UserService"}). Order preserves source order so the listing reads top-down.
   */
  static List<String> extractSymbols(String path, String content) {
    String ext = extensionOf(path);
    List<String> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    switch (ext) {
      case "java":
        emitClassThenMethods(content, JAVA_CLASS, JAVA_METHOD, out, seen);
        break;
      case "kt":
      case "kts":
        emitClassThenMethods(content, JAVA_CLASS, KOTLIN_FUN, out, seen);
        break;
      case "py":
        addClasses(PYTHON_CLASS, content, out, seen);
        addFunctions(PYTHON_DEF, content, out, seen, "def ");
        break;
      case "ts":
      case "tsx":
      case "js":
      case "jsx":
      case "mjs":
      case "cjs":
        addClasses(TS_CLASS, content, out, seen);
        addAlternates(TS_FUNCTION, content, out, seen);
        addFunctionsInClassBodies(TS_METHOD, content, out, seen);
        break;
      case "go":
        addFunctions(GO_FUNC, content, out, seen, "func ");
        break;
      case "rs":
        addFunctions(RUST_FN, content, out, seen, "fn ");
        break;
      case "rb":
        addFunctions(RUBY_DEF, content, out, seen, "def ");
        break;
      default:
        // Unsupported language — leave empty.
    }
    return out;
  }

  private static void emitClassThenMethods(
      String content, Pattern classP, Pattern methodP, List<String> out, Set<String> seen) {
    addClasses(classP, content, out, seen);
    addFunctions(methodP, content, out, seen, "");
  }

  private static void addClasses(Pattern p, String content, List<String> out, Set<String> seen) {
    Matcher m = p.matcher(content);
    while (m.find()) {
      String name = m.group(1);
      if (name == null || seen.contains("class " + name)) continue;
      seen.add("class " + name);
      out.add("class " + name);
    }
  }

  private static void addFunctions(
      Pattern p, String content, List<String> out, Set<String> seen, String prefix) {
    Matcher m = p.matcher(content);
    while (m.find()) {
      String name = m.group(1);
      if (name == null || isReservedKeyword(name)) continue;
      String key = prefix + name;
      if (seen.contains(key)) continue;
      seen.add(key);
      out.add(prefix.isEmpty() ? name : prefix + name);
    }
  }

  /**
   * For TS function declarations the regex has 2 alternation groups (function name vs const-arrow
   * name). Either may be the populated capture group; this helper reads whichever matched.
   */
  private static void addAlternates(Pattern p, String content, List<String> out, Set<String> seen) {
    Matcher m = p.matcher(content);
    while (m.find()) {
      String name = m.group(1) != null ? m.group(1) : m.group(2);
      if (name == null || isReservedKeyword(name) || seen.contains(name)) continue;
      seen.add(name);
      out.add(name);
    }
  }

  /**
   * TS / JS class methods are detected by 2-space-indented function definitions inside class
   * bodies. Skip the JS keywords {@code if}/{@code for}/{@code while}/{@code switch}/{@code
   * catch}/{@code return} that look method-shaped under the regex.
   */
  private static void addFunctionsInClassBodies(
      Pattern p, String content, List<String> out, Set<String> seen) {
    Matcher m = p.matcher(content);
    while (m.find()) {
      String name = m.group(1);
      if (name == null || isReservedKeyword(name) || seen.contains(name)) continue;
      seen.add(name);
      out.add(name);
    }
  }

  private static boolean isSourceFile(String path) {
    String ext = extensionOf(path);
    return switch (ext) {
      case "java", "kt", "kts", "py", "ts", "tsx", "js", "jsx", "mjs", "cjs", "go", "rs", "rb" ->
          true;
      default -> false;
    };
  }

  private static String extensionOf(String path) {
    int dot = path.lastIndexOf('.');
    if (dot < 0 || dot == path.length() - 1) return "";
    return path.substring(dot + 1).toLowerCase();
  }

  /** Reserved-word filter so JS/TS regex doesn't surface {@code if}/{@code for}/etc as methods. */
  private static boolean isReservedKeyword(String name) {
    return switch (name) {
      case "if",
              "for",
              "while",
              "switch",
              "catch",
              "return",
              "do",
              "else",
              "try",
              "finally",
              "new",
              "delete",
              "typeof",
              "void" ->
          true;
      default -> false;
    };
  }
}
