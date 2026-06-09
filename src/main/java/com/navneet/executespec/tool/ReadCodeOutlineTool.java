package com.navneet.executespec.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * W3.1 (vix-leverage, external-audit P2) — read-only structural outline of a Java source file:
 * package, imports, and each type's classes / records / methods / constructors / fields with
 * signatures and line ranges (bodies omitted). Lets the worker LOCALIZE the symbol it needs in a
 * big file without reading the whole thing, then {@code readFile} only the relevant line range —
 * the benchmark + large-customer-repo win. Provider-agnostic.
 *
 * <p><b>Java only (first cut).</b> Backed by JavaParser (a cached, self-contained dep). TS/JS/Py/Go
 * need tree-sitter (not yet a dependency) — {@code name()}-level extension, deferred.
 *
 * <p><b>Flag-gated out of the schema</b> ({@link FlagGatedTool}, {@link #FLAG}, V234, default OFF):
 * when off it is NOT bound (absent from the model schema, RL-2026-05-061 schema-safety incl. the
 * DeepSeek path), so the worker tool set is byte-identical until flipped. Read-only + fail-safe (a
 * syntax error returns a "readFile it directly" note, never an error that blocks the worker).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ReadCodeOutlineTool implements Tool, FlagGatedTool {

  public static final String FLAG = "worker.code-outline.enabled"; // V234, default OFF
  private static final int MAX_CHARS = 12000;

  private final FeatureFlagService flags;

  @Override
  public boolean enabled() {
    return flags.isEnabled(FLAG);
  }

  @Override
  public String name() {
    return "readCodeOutline";
  }

  @Override
  public String description() {
    return "Returns a compact STRUCTURAL outline of a Java source file — package, imports, and each"
        + " type's methods/constructors/fields with signatures and line ranges (bodies"
        + " omitted) — so you can find the exact symbol + line range you need in a large"
        + " file, then readFile only that range instead of the whole file. Java (.java)"
        + " only.";
  }

  @Override
  public String inputSchema() {
    return """
           {
             "type": "object",
             "properties": {
               "path": {
                 "type": "string",
                 "description": "Workspace-relative path to a .java source file"
               }
             },
             "required": ["path"],
             "additionalProperties": false
           }
           """;
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    if (!flags.isEnabled(FLAG)) {
      return ToolResult.err(
          ToolResult.ErrorKind.FORBIDDEN, "code outline is not enabled for this run");
    }
    String path = args.path("path").asText(null);
    if (path == null || path.isBlank()) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, "path is required");
    }
    if (!path.toLowerCase().endsWith(".java")) {
      return ToolResult.err(
          ToolResult.ErrorKind.INVALID_ARGS, "readCodeOutline supports Java (.java) files only");
    }
    try {
      Path resolved = PathSafety.safeResolve(ctx.workspaceRoot(), path);
      if (!Files.exists(resolved)) {
        return ToolResult.err(ToolResult.ErrorKind.NOT_FOUND, "file not found: " + path);
      }
      String outline = outline(path, Files.readString(resolved));
      if (outline.length() > MAX_CHARS) {
        outline = outline.substring(0, MAX_CHARS) + "\n…(outline truncated)…";
      }
      return ToolResult.ok(outline);
    } catch (com.github.javaparser.ParseProblemException ppe) {
      // Fail-safe: a syntax error is not the worker's problem to debug via the outline tool — point
      // it at readFile instead of returning a hard error.
      return ToolResult.ok(
          "(could not parse "
              + path
              + " — syntax error; readFile it directly)\n  "
              + firstLine(ppe.getMessage()));
    } catch (SecurityException se) {
      return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, se.getMessage());
    } catch (Exception e) {
      log.debug("readCodeOutline error path={}: {}", path, e.toString());
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, e.getMessage());
    }
  }

  /** Build the structural outline. Package-private for unit testing. */
  static String outline(String path, String src) {
    CompilationUnit cu = StaticJavaParser.parse(src);
    StringBuilder sb = new StringBuilder("outline: ").append(path).append("\n");
    cu.getPackageDeclaration()
        .ifPresent(p -> sb.append("package ").append(p.getNameAsString()).append("\n"));
    var imports = cu.getImports();
    if (!imports.isEmpty()) {
      sb.append("imports (").append(imports.size()).append("): ");
      sb.append(
          imports.stream()
              .map(i -> i.getNameAsString() + (i.isAsterisk() ? ".*" : ""))
              .limit(40)
              .collect(Collectors.joining(", ")));
      sb.append("\n");
    }
    for (TypeDeclaration<?> type : cu.getTypes()) {
      appendType(sb, type, 0);
    }
    return sb.toString();
  }

  private static void appendType(StringBuilder sb, TypeDeclaration<?> type, int depth) {
    String ind = "  ".repeat(depth);
    String kind;
    if (type instanceof ClassOrInterfaceDeclaration cid) {
      kind = cid.isInterface() ? "interface" : "class";
    } else if (type instanceof EnumDeclaration) {
      kind = "enum";
    } else if (type instanceof RecordDeclaration) {
      kind = "record";
    } else {
      kind = "type";
    }
    sb.append(ind)
        .append(kind)
        .append(" ")
        .append(type.getNameAsString())
        .append(lines(type))
        .append("\n");
    for (BodyDeclaration<?> member : type.getMembers()) {
      if (member instanceof FieldDeclaration f) {
        for (var v : f.getVariables()) {
          sb.append(ind)
              .append("  field ")
              .append(f.getElementType().asString())
              .append(" ")
              .append(v.getNameAsString())
              .append("\n");
        }
      } else if (member instanceof ConstructorDeclaration c) {
        sb.append(ind)
            .append("  ")
            .append(c.getDeclarationAsString(true, false, true))
            .append(lines(c))
            .append("\n");
      } else if (member instanceof MethodDeclaration m) {
        sb.append(ind)
            .append("  ")
            .append(m.getDeclarationAsString(true, false, true))
            .append(lines(m))
            .append("\n");
      } else if (member instanceof TypeDeclaration<?> nested) {
        appendType(sb, nested, depth + 1);
      }
    }
  }

  private static String lines(Node n) {
    return (n.getBegin().isPresent() && n.getEnd().isPresent())
        ? " (L" + n.getBegin().get().line + "-" + n.getEnd().get().line + ")"
        : "";
  }

  private static String firstLine(String s) {
    return s == null ? "" : s.split("\n", 2)[0];
  }
}
