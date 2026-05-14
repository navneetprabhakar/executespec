package com.navneet.executespec.codeintel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;

import lombok.extern.slf4j.Slf4j;

/**
 * PR-N1 (2026-05-14) — Java AST-based symbol extractor. Replaces the regex path in {@link
 * CodeMapGenerator#extractSymbols(String, String)} for {@code .java} files specifically.
 *
 * <p>Why we swapped from regex to AST for Java only:
 *
 * <ul>
 *   <li>Multi-line method signatures with generics + throws clauses — regex either misses them or
 *       over-matches into bodies. AST handles them as a single node.
 *   <li>Sealed classes + records + permits clauses (Java 17+) — the regex predates these.
 *   <li>Nested types — regex couldn't reliably tell {@code class Inner} apart from a string literal
 *       mentioning the word "class".
 *   <li>Lambda-only "methods" that the regex inadvertently included — AST distinguishes declared
 *       methods from lambdas.
 * </ul>
 *
 * <p>Other languages keep their regex extractor in {@link CodeMapGenerator}; JavaParser only helps
 * Java. Python's {@code ast} module would do the same but it requires a Python runtime which we
 * don't ship; same for TypeScript / Go / Rust. The cost-benefit only justifies the dep on Java
 * because that's the language with the most pathological regex edge cases in the audit sample.
 *
 * <p>Best-effort: malformed source returns whatever symbols JavaParser DID manage to extract,
 * falling back to an empty list if parsing failed entirely. The regex extractor is still available
 * as the safety net (callers in {@link CodeMapGenerator} can choose to fall back).
 */
@Slf4j
public final class JavaAstSymbolExtractor {

  // JavaParser instance is thread-safe for parsing (per JavaParser docs).
  private static final JavaParser PARSER =
      new JavaParser(
          new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

  private JavaAstSymbolExtractor() {}

  /**
   * Extracts class / interface / record / enum / method names from {@code content} in declaration
   * order. Empty list on parse failure.
   *
   * <p>Output shape matches the regex extractor's:
   *
   * <ul>
   *   <li>{@code "class UserService"} / {@code "record UserId"} / {@code "interface Repository"} /
   *       {@code "enum Status"} for type declarations
   *   <li>Plain identifier (e.g. {@code "createUser"}) for methods
   * </ul>
   *
   * <p>Returns a deduped {@code List<String>} preserving first-occurrence order so the codemap
   * reads top-down.
   */
  public static List<String> extract(String content) {
    if (content == null || content.isBlank()) return List.of();
    try {
      ParseResult<CompilationUnit> result = PARSER.parse(content);
      Optional<CompilationUnit> unit = result.getResult();
      if (unit.isEmpty()) {
        // Parse failure (malformed Java) — empty list. Caller can fall back to regex.
        return List.of();
      }
      List<String> out = new ArrayList<>();
      Set<String> seen = new LinkedHashSet<>();
      unit.get()
          .findAll(ClassOrInterfaceDeclaration.class)
          .forEach(
              c -> {
                String prefix = c.isInterface() ? "interface " : "class ";
                addIfNew(prefix + c.getNameAsString(), out, seen);
              });
      unit.get()
          .findAll(RecordDeclaration.class)
          .forEach(r -> addIfNew("record " + r.getNameAsString(), out, seen));
      unit.get()
          .findAll(EnumDeclaration.class)
          .forEach(e -> addIfNew("enum " + e.getNameAsString(), out, seen));
      unit.get()
          .findAll(MethodDeclaration.class)
          .forEach(m -> addIfNew(m.getNameAsString(), out, seen));
      return out;
    } catch (Exception e) {
      // Defensive: a JavaParser bug or out-of-memory on a truly enormous file shouldn't crash
      // the entire codemap regen. Log + empty.
      log.debug("JavaAstSymbolExtractor parse failed: {}", e.getMessage());
      return List.of();
    }
  }

  private static void addIfNew(String name, List<String> out, Set<String> seen) {
    if (seen.add(name)) out.add(name);
  }
}
