package com.navneet.executespec.contractsurface;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

/**
 * Deterministically inventories Spring MVC HTTP mappings from a source workspace.
 *
 * <p>This extractor is deliberately provider-free. It combines the configured servlet context path,
 * class-level mappings and method-level mappings into canonical method/path rows. It is not a Java
 * compiler; unsupported dynamic annotation expressions are ignored rather than guessed.
 */
@Component
public class SpringHttpSurfaceExtractor {

  private static final Pattern CLASS_DECLARATION =
      Pattern.compile("\\b(?:class|interface)\\s+[A-Za-z_$][\\w$]*");
  private static final Pattern MAPPING =
      Pattern.compile(
          "@(Get|Post|Put|Patch|Delete|Request)Mapping\\s*(?:\\((.*?)\\))?", Pattern.DOTALL);
  private static final Pattern EXPLICIT_PATH =
      Pattern.compile("\\b(?:value|path)\\s*=\\s*(\\{[^}]*}|\"[^\"]*\"|'[^']*')", Pattern.DOTALL);
  private static final Pattern QUOTED = Pattern.compile("[\"']([^\"']*)[\"']");
  private static final Pattern REQUEST_METHOD =
      Pattern.compile("RequestMethod\\.(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE)");
  private static final Pattern PROPERTIES_CONTEXT =
      Pattern.compile("(?m)^\\s*server\\.servlet\\.context-path\\s*[=:]\\s*([^#\\s]+)");
  private static final Pattern YAML_CONTEXT =
      Pattern.compile(
          "(?ms)^\\s*server\\s*:\\s*$.*?^\\s+servlet\\s*:\\s*$.*?^\\s+context-path\\s*:\\s*"
              + "([^#\\r\\n]+)");

  private static final Set<String> EXCLUDED_SEGMENTS =
      Set.of(
          ".git",
          ".agent-context",
          "target",
          "build",
          "dist",
          "node_modules",
          "test",
          "tests",
          "generated");

  public SurfaceSnapshot extract(Path workspaceRoot) {
    if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
      return SurfaceSnapshot.of(List.of());
    }
    String contextPath = findContextPath(workspaceRoot);
    List<Route> routes = new ArrayList<>();
    try (Stream<Path> files = Files.walk(workspaceRoot, 12)) {
      files
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".java"))
          .filter(path -> isProductionSource(workspaceRoot, path))
          .sorted()
          .forEach(path -> extractFile(workspaceRoot, path, contextPath, routes));
    } catch (IOException e) {
      throw new SurfaceExtractionException(
          "could not inventory Spring HTTP surface: " + e.getMessage(), e);
    }
    return SurfaceSnapshot.of(routes);
  }

  private void extractFile(Path root, Path file, String contextPath, List<Route> routes) {
    final String source;
    try {
      source = Files.readString(file);
    } catch (IOException e) {
      throw new SurfaceExtractionException("could not read " + root.relativize(file), e);
    }
    Matcher classMatcher = CLASS_DECLARATION.matcher(source);
    if (!classMatcher.find()) return;

    // Do not search backward for '}' here: annotation array syntax itself contains braces and
    // would cut off @RequestMapping(path={"/v1", "/v2"}). One production controller per source
    // file is the supported deterministic subset, so the complete pre-declaration prefix is safe.
    String classAnnotations = source.substring(0, classMatcher.start());
    List<String> classPaths = mappingPaths(classAnnotations, true);
    if (classPaths.isEmpty()) classPaths = List.of("/");

    Matcher mapping = MAPPING.matcher(source);
    while (mapping.find()) {
      if (mapping.start() < classMatcher.start()) continue;
      int declarationEnd = nextDeclarationBoundary(source, mapping.end());
      if (declarationEnd < 0) continue;
      String between = source.substring(mapping.end(), declarationEnd);
      if (CLASS_DECLARATION.matcher(between).find()) continue;

      String annotation = mapping.group();
      List<String> methods = methods(mapping.group(1), annotation);
      List<String> methodPaths = mappingPaths(annotation, false);
      if (methodPaths.isEmpty()) methodPaths = List.of("/");
      int line =
          1 + (int) source.substring(0, mapping.start()).chars().filter(c -> c == '\n').count();
      String sourceLocation = root.relativize(file).toString().replace('\\', '/') + ":" + line;

      for (String classPath : classPaths) {
        for (String methodPath : methodPaths) {
          String fullPath = join(contextPath, classPath, methodPath);
          for (String method : methods) {
            routes.add(new Route(method, fullPath, sourceLocation));
          }
        }
      }
    }
  }

  private static int nextDeclarationBoundary(String source, int from) {
    int brace = source.indexOf('{', from);
    int semicolon = source.indexOf(';', from);
    if (brace < 0) return semicolon;
    if (semicolon < 0) return brace;
    return Math.min(brace, semicolon);
  }

  private static List<String> methods(String mappingType, String annotation) {
    if (!"Request".equals(mappingType)) {
      return List.of(mappingType.toUpperCase(Locale.ROOT));
    }
    List<String> methods = new ArrayList<>();
    Matcher matcher = REQUEST_METHOD.matcher(annotation);
    while (matcher.find()) methods.add(matcher.group(1));
    return methods.isEmpty() ? List.of("ANY") : methods.stream().distinct().sorted().toList();
  }

  private static List<String> mappingPaths(String annotations, boolean classLevel) {
    Matcher matcher = MAPPING.matcher(annotations);
    List<String> paths = new ArrayList<>();
    while (matcher.find()) {
      if (classLevel && !"Request".equals(matcher.group(1))) continue;
      String args = matcher.group(2);
      if (args == null || args.isBlank()) {
        paths.add("/");
        continue;
      }
      Matcher explicit = EXPLICIT_PATH.matcher(args);
      String pathExpression = explicit.find() ? explicit.group(1) : leadingPathExpression(args);
      if (pathExpression == null) continue;
      Matcher quoted = QUOTED.matcher(pathExpression);
      while (quoted.find()) paths.add(quoted.group(1));
    }
    return paths.stream().distinct().toList();
  }

  private static String leadingPathExpression(String args) {
    String stripped = args.stripLeading();
    if (stripped.startsWith("\"") || stripped.startsWith("'")) {
      Matcher quoted = QUOTED.matcher(stripped);
      return quoted.find() ? quoted.group() : null;
    }
    if (stripped.startsWith("{")) {
      int close = stripped.indexOf('}');
      return close >= 0 ? stripped.substring(0, close + 1) : null;
    }
    return null;
  }

  private static String findContextPath(Path root) {
    List<Path> candidates = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root, 10)) {
      files
          .filter(Files::isRegularFile)
          .filter(SpringHttpSurfaceExtractor::isApplicationConfiguration)
          .filter(path -> isProductionSource(root, path))
          .sorted()
          .forEach(candidates::add);
    } catch (IOException e) {
      throw new SurfaceExtractionException("could not inspect Spring configuration", e);
    }
    for (Path candidate : candidates) {
      try {
        String content = Files.readString(candidate);
        Matcher properties = PROPERTIES_CONTEXT.matcher(content);
        if (properties.find()) return normalizePath(unquote(properties.group(1).strip()));
        Matcher yaml = YAML_CONTEXT.matcher(content);
        if (yaml.find()) return normalizePath(unquote(yaml.group(1).strip()));
      } catch (IOException e) {
        throw new SurfaceExtractionException("could not read " + root.relativize(candidate), e);
      }
    }
    return "/";
  }

  private static boolean isApplicationConfiguration(Path path) {
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.startsWith("application")
        && (name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml"));
  }

  private static boolean isProductionSource(Path root, Path path) {
    Path relative = root.relativize(path);
    for (Path segment : relative) {
      if (EXCLUDED_SEGMENTS.contains(segment.toString().toLowerCase(Locale.ROOT))) return false;
    }
    String normalized = relative.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    return !normalized.contains("/src/test/") && !normalized.startsWith("src/test/");
  }

  static String join(String... segments) {
    StringBuilder joined = new StringBuilder();
    for (String segment : segments) {
      String normalized = normalizePath(segment);
      if (!"/".equals(normalized)) joined.append(normalized);
    }
    return joined.isEmpty() ? "/" : normalizePath(joined.toString());
  }

  static String normalizePath(String value) {
    if (value == null || value.isBlank() || "/".equals(value.strip())) return "/";
    String normalized = value.strip().replace('\\', '/').replaceAll("/+", "/");
    if (!normalized.startsWith("/")) normalized = "/" + normalized;
    if (normalized.length() > 1 && normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  private static String unquote(String value) {
    if (value.length() >= 2
        && ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'")))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  public record Route(String method, String path, String source) {
    public Route {
      method = method == null ? "ANY" : method.toUpperCase(Locale.ROOT);
      path = normalizePath(path);
      source = source == null ? "" : source;
    }

    public RouteKey key() {
      return new RouteKey(method, path);
    }
  }

  public record RouteKey(String method, String path) implements Comparable<RouteKey> {
    public RouteKey {
      method = method == null ? "ANY" : method.toUpperCase(Locale.ROOT);
      path = normalizePath(path);
    }

    @Override
    public int compareTo(RouteKey other) {
      int methodOrder = method.compareTo(other.method);
      return methodOrder != 0 ? methodOrder : path.compareTo(other.path);
    }
  }

  public record SurfaceSnapshot(int schemaVersion, List<Route> routes, String digest) {
    static SurfaceSnapshot of(List<Route> discovered) {
      List<Route> canonical =
          discovered.stream()
              .sorted(
                  Comparator.comparing(Route::method)
                      .thenComparing(Route::path)
                      .thenComparing(Route::source))
              .distinct()
              .toList();
      return new SurfaceSnapshot(1, canonical, sha256(canonical));
    }

    public Set<RouteKey> keys() {
      Set<RouteKey> keys = new LinkedHashSet<>();
      routes.forEach(route -> keys.add(route.key()));
      return Set.copyOf(keys);
    }

    private static String sha256(List<Route> routes) {
      try {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Route route : routes) {
          digest.update(
              (route.method() + "\t" + route.path() + "\t" + route.source() + "\n")
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
      } catch (java.security.NoSuchAlgorithmException impossible) {
        throw new IllegalStateException("SHA-256 unavailable", impossible);
      }
    }
  }

  public static final class SurfaceExtractionException extends RuntimeException {
    public SurfaceExtractionException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
