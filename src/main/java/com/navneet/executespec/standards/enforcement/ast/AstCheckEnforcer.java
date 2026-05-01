package com.navneet.executespec.standards.enforcement.ast;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.standards.model.Standard;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * E3.7 + E3.8 (combined per eval-doc cut from 10×5 to 3×2): AST-check enforcer. Three rule kinds,
 * each language-aware via per-language regex tuned for the import + declaration syntax of that
 * language.
 *
 * <h2>Rule kinds (v1)</h2>
 *
 * <ul>
 *   <li>{@code forbidden-import} — files in scope must not import the specified package/module
 *       ({@code parameters.import})
 *   <li>{@code naming-pattern} — symbols (class/function names) matching a declaration pattern must
 *       match a name regex ({@code parameters.regex})
 *   <li>{@code dependency-direction} — files in scope (path glob) must not import from another
 *       scope ({@code parameters.from / parameters.forbidden_target})
 * </ul>
 *
 * <h2>Language coverage</h2>
 *
 * <p>Java + TypeScript per the eval-doc cut. Other languages can land via the same regex-driven
 * shape; the upgrade to a real AST parser (JavaParser / ts-morph) is a follow-up logged in §13.
 *
 * <p>This is a pure function — takes file paths + reads bytes via the supplied reader callback (so
 * tests don't need real files on disk).
 */
@Component
@Slf4j
public class AstCheckEnforcer {

  @FunctionalInterface
  public interface FileReader {
    /** Returns null when the file can't be read. */
    String read(Path path);
  }

  public List<AstViolation> enforce(
      Standard.AstCheckEnforcement enforcement, List<Path> sourceFiles, FileReader reader) {
    if (enforcement == null || enforcement.ruleKind() == null) return List.of();
    if (sourceFiles == null || sourceFiles.isEmpty()) return List.of();
    return switch (enforcement.ruleKind().toLowerCase()) {
      case "forbidden-import" -> forbiddenImport(enforcement.parameters(), sourceFiles, reader);
      case "naming-pattern" -> namingPattern(enforcement.parameters(), sourceFiles, reader);
      case "dependency-direction" ->
          dependencyDirection(enforcement.parameters(), sourceFiles, reader);
      default -> {
        log.debug("[AstCheckEnforcer] unsupported rule kind '{}' — skip", enforcement.ruleKind());
        yield List.of();
      }
    };
  }

  /** Convenience overload that reads files from disk. */
  public List<AstViolation> enforce(
      Standard.AstCheckEnforcement enforcement, List<Path> sourceFiles) {
    return enforce(
        enforcement,
        sourceFiles,
        p -> {
          try {
            return Files.readString(p, StandardCharsets.UTF_8);
          } catch (Exception ex) {
            return null;
          }
        });
  }

  // ── Rule kinds ──────────────────────────────────────────────────────────

  List<AstViolation> forbiddenImport(
      Map<String, Object> params, List<Path> sourceFiles, FileReader reader) {
    String forbidden = strOrNull(params, "import");
    if (forbidden == null || forbidden.isBlank()) return List.of();
    List<AstViolation> out = new ArrayList<>();
    for (Path p : sourceFiles) {
      String content = reader.read(p);
      if (content == null) continue;
      Lang lang = Lang.of(p);
      if (importMatches(content, lang, forbidden)) {
        out.add(
            new AstViolation(
                "forbidden-import",
                p.toString(),
                0,
                "Forbidden import '" + forbidden + "' present in " + p));
      }
    }
    return out;
  }

  List<AstViolation> namingPattern(
      Map<String, Object> params, List<Path> sourceFiles, FileReader reader) {
    String regex = strOrNull(params, "regex");
    String declarationKindRaw = strOrNull(params, "declaration");
    if (regex == null) return List.of();
    Pattern namePattern;
    try {
      namePattern = Pattern.compile(regex);
    } catch (Exception ex) {
      log.warn("[AstCheckEnforcer] naming-pattern: invalid regex '{}': {}", regex, ex.getMessage());
      return List.of();
    }
    DeclarationKind decl = DeclarationKind.parse(declarationKindRaw);

    List<AstViolation> out = new ArrayList<>();
    for (Path p : sourceFiles) {
      String content = reader.read(p);
      if (content == null) continue;
      Lang lang = Lang.of(p);
      for (Declaration d : extractDeclarations(content, lang, decl)) {
        if (!namePattern.matcher(d.name).matches()) {
          out.add(
              new AstViolation(
                  "naming-pattern",
                  p.toString(),
                  d.line,
                  "Name '" + d.name + "' (" + d.kind + ") does not match required regex " + regex));
        }
      }
    }
    return out;
  }

  List<AstViolation> dependencyDirection(
      Map<String, Object> params, List<Path> sourceFiles, FileReader reader) {
    String fromGlob = strOrNull(params, "from");
    String forbiddenTarget = strOrNull(params, "forbidden_target");
    if (fromGlob == null || forbiddenTarget == null) return List.of();
    List<AstViolation> out = new ArrayList<>();
    for (Path p : sourceFiles) {
      // Match path against the from-glob via DetectorUtils glob (already
      // imported from E2.2). Only files in the source scope are checked.
      if (!com.navneet.executespec.contracts.detection.DetectorUtils.matchesGlob(
          p.toString(), fromGlob)) continue;
      String content = reader.read(p);
      if (content == null) continue;
      Lang lang = Lang.of(p);
      if (importMatches(content, lang, forbiddenTarget)) {
        out.add(
            new AstViolation(
                "dependency-direction",
                p.toString(),
                0,
                "Layer violation: "
                    + p
                    + " (in scope "
                    + fromGlob
                    + ") imports forbidden target '"
                    + forbiddenTarget
                    + "'"));
      }
    }
    return out;
  }

  // ── Language-aware regex helpers ────────────────────────────────────────

  /** True when the content contains an import that matches the target. */
  boolean importMatches(String content, Lang lang, String target) {
    Pattern p = importRegex(lang);
    Matcher m = p.matcher(content);
    while (m.find()) {
      String imported = m.group(1);
      if (imported == null) continue;
      if (imported.equals(target)
          || imported.startsWith(target + ".")
          || imported.startsWith(target + "/")) return true;
    }
    return false;
  }

  private Pattern importRegex(Lang lang) {
    return switch (lang) {
      case JAVA -> Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;");
      case TYPESCRIPT ->
          Pattern.compile("(?m)^\\s*import\\s+(?:[\\s\\S]*?\\bfrom\\s+)?['\"]([^'\"]+)['\"]");
      case OTHER -> Pattern.compile("(?m)^\\s*(?:import|from|require\\()['\"]?([\\w./@-]+)['\"]?");
    };
  }

  private List<Declaration> extractDeclarations(String content, Lang lang, DeclarationKind kind) {
    List<Declaration> out = new ArrayList<>();
    Pattern p =
        switch (lang) {
          case JAVA ->
              kind == DeclarationKind.METHOD
                  ? Pattern.compile(
                      "(?m)^\\s*(?:public|private|protected)?\\s*(?:static\\s+)?[\\w<>,\\s]+\\s+(\\w+)\\s*\\(")
                  : Pattern.compile(
                      "(?m)^\\s*(?:public|private|protected)?\\s*(?:abstract\\s+|final\\s+)?\\bclass\\s+(\\w+)");
          case TYPESCRIPT ->
              kind == DeclarationKind.METHOD
                  ? Pattern.compile("(?m)^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+(\\w+)")
                  : Pattern.compile("(?m)^\\s*(?:export\\s+)?(?:abstract\\s+)?class\\s+(\\w+)");
          case OTHER -> Pattern.compile("(\\w+)"); // never used directly
        };
    Matcher m = p.matcher(content);
    while (m.find()) {
      String name = m.group(1);
      int line = lineFor(content, m.start());
      out.add(new Declaration(name, kind.label(), line));
    }
    return out;
  }

  private static int lineFor(String content, int charOffset) {
    int line = 1;
    for (int i = 0; i < charOffset && i < content.length(); i++) {
      if (content.charAt(i) == '\n') line++;
    }
    return line;
  }

  private static String strOrNull(Map<String, Object> map, String key) {
    if (map == null) return null;
    Object v = map.get(key);
    return v == null ? null : v.toString();
  }

  // ── Inner types ─────────────────────────────────────────────────────────

  enum Lang {
    JAVA,
    TYPESCRIPT,
    OTHER;

    static Lang of(Path p) {
      String s = p.toString().toLowerCase();
      if (s.endsWith(".java")) return JAVA;
      if (s.endsWith(".ts") || s.endsWith(".tsx")) return TYPESCRIPT;
      return OTHER;
    }
  }

  enum DeclarationKind {
    CLASS,
    METHOD;

    static DeclarationKind parse(String s) {
      if (s == null) return CLASS;
      return s.equalsIgnoreCase("method") || s.equalsIgnoreCase("function") ? METHOD : CLASS;
    }

    String label() {
      return name().toLowerCase();
    }
  }

  /** internal — extracted name + line. */
  record Declaration(String name, String kind, int line) {}

  /**
   * @param ruleKind matches the rule.kind from the standard's YAML
   * @param file relative file path
   * @param line 1-based line; 0 when file-level
   * @param message human-readable message
   */
  public record AstViolation(String ruleKind, String file, int line, String message) {}
}
