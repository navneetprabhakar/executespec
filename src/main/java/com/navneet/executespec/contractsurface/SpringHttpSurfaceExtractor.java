package com.navneet.executespec.contractsurface;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.navneet.executespec.contractsurface.SpringContractSourceInventory.SourceFile;
import com.navneet.executespec.contractsurface.SpringHttpSurfaceSnapshot.ConfigurationSource;

import org.springframework.stereotype.Component;

/**
 * Deterministically inventories Spring MVC HTTP mappings from a source workspace.
 *
 * <p>This extractor is deliberately provider-free. It combines the configured servlet context path,
 * class-level mappings and method-level mappings into canonical method/path rows. It intentionally
 * supports only literal Spring mapping declarations. Dynamic, composed/meta-annotated, malformed,
 * or otherwise ambiguous mappings fail extraction instead of disappearing from the protected
 * brownfield baseline.
 */
@Component
public class SpringHttpSurfaceExtractor {

  private static final Pattern PROPERTIES_CONTEXT =
      Pattern.compile("(?m)^\\s*server\\.servlet\\.context-path\\s*[=:]\\s*([^#\\r\\n]*)");
  private static final Pattern YAML_CONTEXT =
      Pattern.compile(
          "(?ms)^\\s*server\\s*:\\s*$.*?^\\s+servlet\\s*:\\s*$.*?^\\s+context-path\\s*:\\s*"
              + "([^#\\r\\n]+)");
  private static final Set<String> HTTP_METHODS =
      Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE");
  private static final Map<String, String> MAPPING_METHODS =
      Map.of(
          "GetMapping", "GET",
          "PostMapping", "POST",
          "PutMapping", "PUT",
          "PatchMapping", "PATCH",
          "DeleteMapping", "DELETE");

  public SpringHttpSurfaceSnapshot extract(Path workspaceRoot) {
    return extract(workspaceRoot, SpringContractSourceInventory.DEFAULT_LIMITS);
  }

  /**
   * Extracts the canonical surface AND reports whether the workspace contains any production Java
   * source carrying a Spring MVC mapping annotation the extractor recognizes.
   *
   * <p>V367 principle: an empty successful extraction over a Node, Go, Python, Rust, or plain-Java
   * repository is NOT positive Spring MVC evidence — it means the extractor's evidence class is not
   * applicable to that stack. Callers that convert "route absent from the extracted surface" into a
   * failing verdict must first check {@link WorkspaceExtraction#springMvcMappingSourcePresent()} so
   * a non-Spring deliverable cannot fail by construction.
   */
  public WorkspaceExtraction extractWithApplicability(Path workspaceRoot) {
    return extractWithApplicability(workspaceRoot, SpringContractSourceInventory.DEFAULT_LIMITS);
  }

  SpringHttpSurfaceSnapshot extract(
      Path workspaceRoot, SpringContractSourceInventory.TraversalLimits limits) {
    return extractWithApplicability(workspaceRoot, limits).snapshot();
  }

  private WorkspaceExtraction extractWithApplicability(
      Path workspaceRoot, SpringContractSourceInventory.TraversalLimits limits) {
    if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
      return new WorkspaceExtraction(SpringHttpSurfaceSnapshot.of(List.of(), List.of()), false);
    }
    SpringContractSourceInventory.Inventory inventory =
        SpringContractSourceInventory.scan(workspaceRoot, limits);
    List<ParsedSource> sources = parseSources(workspaceRoot, inventory.javaSources());
    rejectComposedMappings(workspaceRoot, sources);
    boolean springMvcMappingSourcePresent =
        sources.stream().anyMatch(SpringHttpSurfaceExtractor::containsMappingAnnotation);
    Map<Path, ContextPath> contextPaths = new LinkedHashMap<>();
    List<ConfigurationSource> configurationSources = new ArrayList<>();
    List<Route> routes = new ArrayList<>();
    for (ParsedSource source : sources) {
      Path moduleRoot = moduleRoot(workspaceRoot, source.path());
      ContextPath contextPath =
          contextPaths.computeIfAbsent(
              moduleRoot,
              ignored ->
                  findContextPath(
                      workspaceRoot, moduleRoot, inventory.applicationConfigurations()));
      configurationSources.addAll(contextPath.sources());
      extractCompilationUnit(workspaceRoot, source, contextPath.value(), routes);
    }
    return new WorkspaceExtraction(
        SpringHttpSurfaceSnapshot.of(routes, configurationSources), springMvcMappingSourcePresent);
  }

  private static boolean containsMappingAnnotation(ParsedSource source) {
    return source.compilationUnit().findAll(AnnotationExpr.class).stream()
        .anyMatch(SpringHttpSurfaceExtractor::isMappingAnnotation);
  }

  /**
   * Extracts the same canonical surface from an immutable, caller-supplied source snapshot.
   *
   * <p>The map key is the repository-relative path and the value is the exact file content read at
   * one source revision. This is the remote-repository companion to {@link #extract(Path)}: it lets
   * codemap generation reuse the brownfield extractor without writing a temporary checkout or
   * silently dropping servlet configuration provenance.
   */
  public SpringHttpSurfaceSnapshot extract(Map<String, String> sourceSnapshot) {
    if (sourceSnapshot == null || sourceSnapshot.isEmpty()) {
      return SpringHttpSurfaceSnapshot.of(List.of(), List.of());
    }
    Path root = Path.of("/");
    List<ParsedSource> sources = new ArrayList<>();
    sourceSnapshot.entrySet().stream()
        .filter(entry -> entry.getKey() != null && entry.getKey().endsWith(".java"))
        .filter(entry -> isProductionRelativePath(entry.getKey()))
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              try {
                sources.add(
                    new ParsedSource(
                        root.resolve(entry.getKey()), parseCompilationUnit(entry.getValue())));
              } catch (ParseProblemException failure) {
                throw new SurfaceExtractionException(
                    "could not parse Spring source " + entry.getKey(), failure);
              }
            });
    rejectComposedMappings(root, sources);
    Map<Path, ContextPath> contextPaths = new LinkedHashMap<>();
    List<ConfigurationSource> configurationSources = new ArrayList<>();
    List<Route> routes = new ArrayList<>();
    for (ParsedSource source : sources) {
      Path moduleRoot = moduleRoot(root, source.path());
      ContextPath contextPath =
          contextPaths.computeIfAbsent(
              moduleRoot, ignored -> findContextPath(root, moduleRoot, sourceSnapshot));
      configurationSources.addAll(contextPath.sources());
      extractCompilationUnit(root, source, contextPath.value(), routes);
    }
    return SpringHttpSurfaceSnapshot.of(routes, configurationSources);
  }

  private static List<ParsedSource> parseSources(Path root, List<SourceFile> javaSources) {
    List<ParsedSource> parsedSources = new ArrayList<>();
    for (SourceFile source : javaSources) {
      try {
        parsedSources.add(new ParsedSource(source.path(), parseCompilationUnit(source.content())));
      } catch (ParseProblemException failure) {
        throw new SurfaceExtractionException(
            "could not parse Spring source " + relative(root, source.path()), failure);
      }
    }
    return List.copyOf(parsedSources);
  }

  private static CompilationUnit parseCompilationUnit(String source) {
    ParserConfiguration configuration =
        new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    var result = new JavaParser(configuration).parse(source);
    if (!result.isSuccessful() || result.getResult().isEmpty()) {
      throw new ParseProblemException(result.getProblems());
    }
    return result.getResult().orElseThrow();
  }

  private static void rejectComposedMappings(Path root, List<ParsedSource> sources) {
    for (ParsedSource source : sources) {
      for (AnnotationDeclaration declaration :
          source.compilationUnit().findAll(AnnotationDeclaration.class)) {
        Optional<AnnotationExpr> mapping =
            declaration.getAnnotations().stream()
                .filter(SpringHttpSurfaceExtractor::isMappingAnnotation)
                .findFirst();
        if (mapping.isPresent()) {
          throw unsupported(
              root,
              source.path(),
              mapping.get(),
              "composed/meta-annotated Spring mappings are not supported");
        }
      }
    }
  }

  private static void extractCompilationUnit(
      Path root, ParsedSource source, String contextPath, List<Route> routes) {
    for (ClassOrInterfaceDeclaration type :
        source.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
      rejectUnknownMappingAnnotations(root, source.path(), type);
      List<AnnotationExpr> classMappings = mappingAnnotations(type);
      if (classMappings.size() > 1) {
        throw unsupported(
            root,
            source.path(),
            classMappings.get(1),
            "multiple class-level mappings are ambiguous");
      }
      if (!classMappings.isEmpty() && !"RequestMapping".equals(simpleName(classMappings.get(0)))) {
        throw unsupported(
            root,
            source.path(),
            classMappings.get(0),
            "only @RequestMapping is supported at class level");
      }
      List<String> classPaths =
          classMappings.isEmpty()
              ? List.of("/")
              : mappingPaths(root, source.path(), classMappings.get(0));

      for (MethodDeclaration method : type.getMethods()) {
        rejectUnknownMappingAnnotations(root, source.path(), method);
        List<AnnotationExpr> methodMappings = mappingAnnotations(method);
        if (methodMappings.isEmpty()) continue;
        if (methodMappings.size() > 1) {
          throw unsupported(
              root,
              source.path(),
              methodMappings.get(1),
              "multiple method-level mappings are ambiguous");
        }
        AnnotationExpr mapping = methodMappings.get(0);
        List<String> methods = methods(root, source.path(), mapping);
        List<String> methodPaths = mappingPaths(root, source.path(), mapping);
        int line = mapping.getBegin().map(position -> position.line).orElse(1);
        String sourceLocation = relative(root, source.path()) + ":" + line;

        for (String classPath : classPaths) {
          for (String methodPath : methodPaths) {
            String fullPath = join(contextPath, classPath, methodPath);
            for (String httpMethod : methods) {
              routes.add(new Route(httpMethod, fullPath, sourceLocation));
            }
          }
        }
      }
    }
  }

  private static List<AnnotationExpr> mappingAnnotations(NodeWithAnnotations<?> declaration) {
    return declaration.getAnnotations().stream()
        .filter(SpringHttpSurfaceExtractor::isMappingAnnotation)
        .toList();
  }

  private static boolean isMappingAnnotation(AnnotationExpr annotation) {
    String name = simpleName(annotation);
    return "RequestMapping".equals(name) || MAPPING_METHODS.containsKey(name);
  }

  private static void rejectUnknownMappingAnnotations(
      Path root, Path file, NodeWithAnnotations<?> declaration) {
    declaration.getAnnotations().stream()
        .filter(annotation -> simpleName(annotation).endsWith("Mapping"))
        .filter(annotation -> !isMappingAnnotation(annotation))
        .findFirst()
        .ifPresent(
            annotation -> {
              throw unsupported(
                  root,
                  file,
                  annotation,
                  "unknown/composed mapping annotation cannot be inventoried");
            });
  }

  private static String simpleName(AnnotationExpr annotation) {
    return annotation.getName().getIdentifier();
  }

  private static List<String> methods(Path root, Path file, AnnotationExpr annotation) {
    String name = simpleName(annotation);
    if (!"RequestMapping".equals(name)) {
      return List.of(MAPPING_METHODS.get(name));
    }
    Optional<Expression> expression = member(annotation, "method");
    if (expression.isEmpty()) return List.of("ANY");
    List<String> methods = new ArrayList<>();
    for (Expression candidate : arrayElements(expression.get())) {
      if (!(candidate instanceof FieldAccessExpr field)
          || !"RequestMethod".equals(field.getScope().toString())
          || !HTTP_METHODS.contains(field.getNameAsString())) {
        throw unsupported(
            root, file, annotation, "dynamic or unsupported RequestMapping method expression");
      }
      methods.add(field.getNameAsString());
    }
    if (methods.isEmpty()) {
      throw unsupported(root, file, annotation, "empty RequestMapping method array");
    }
    return methods.stream().distinct().sorted().toList();
  }

  private static List<String> mappingPaths(Path root, Path file, AnnotationExpr annotation) {
    if (annotation instanceof MarkerAnnotationExpr) return List.of("/");
    Expression expression;
    if (annotation instanceof SingleMemberAnnotationExpr single) {
      expression = single.getMemberValue();
    } else if (annotation instanceof NormalAnnotationExpr normal) {
      Optional<Expression> value = pairValue(normal, "value");
      Optional<Expression> path = pairValue(normal, "path");
      if (value.isPresent() && path.isPresent()) {
        throw unsupported(root, file, annotation, "mapping declares both value and path");
      }
      Optional<Expression> selected = value.or(() -> path);
      if (selected.isEmpty()) return List.of("/");
      expression = selected.get();
    } else {
      throw unsupported(root, file, annotation, "unsupported mapping annotation syntax");
    }
    List<String> paths = new ArrayList<>();
    for (Expression candidate : arrayElements(expression)) {
      if (!(candidate instanceof StringLiteralExpr literal)) {
        throw unsupported(root, file, annotation, "dynamic mapping path expression");
      }
      paths.add(literal.asString());
    }
    if (paths.isEmpty()) {
      throw unsupported(root, file, annotation, "empty mapping path array");
    }
    return paths.stream().distinct().toList();
  }

  private static Optional<Expression> member(AnnotationExpr annotation, String name) {
    if (!(annotation instanceof NormalAnnotationExpr normal)) return Optional.empty();
    return pairValue(normal, name);
  }

  private static Optional<Expression> pairValue(NormalAnnotationExpr annotation, String name) {
    return annotation.getPairs().stream()
        .filter(pair -> name.equals(pair.getNameAsString()))
        .map(MemberValuePair::getValue)
        .findFirst();
  }

  private static List<Expression> arrayElements(Expression expression) {
    if (expression instanceof ArrayInitializerExpr array) {
      return List.copyOf(array.getValues());
    }
    return List.of(expression);
  }

  private static Path moduleRoot(Path workspaceRoot, Path javaFile) {
    Path relative = workspaceRoot.relativize(javaFile);
    for (int i = 0; i + 2 < relative.getNameCount(); i++) {
      if ("src".equals(relative.getName(i).toString())
          && "main".equals(relative.getName(i + 1).toString())
          && "java".equals(relative.getName(i + 2).toString())) {
        return i == 0 ? workspaceRoot : workspaceRoot.resolve(relative.subpath(0, i));
      }
    }
    return workspaceRoot;
  }

  private static ContextPath findContextPath(
      Path workspaceRoot, Path moduleRoot, List<SourceFile> applicationConfigurations) {
    List<SourceFile> candidates =
        applicationConfigurations.stream()
            .filter(source -> moduleRoot.equals(resourceModuleRoot(workspaceRoot, source.path())))
            .sorted()
            .toList();
    Map<String, ConfigurationSource> configuredPaths = new LinkedHashMap<>();
    for (SourceFile candidate : candidates) {
      String content = candidate.content();
      Matcher properties = PROPERTIES_CONTEXT.matcher(content);
      while (properties.find()) {
        registerContextPath(
            workspaceRoot,
            candidate.path(),
            properties.group(1),
            lineAt(content, properties.start()),
            configuredPaths);
      }
      Matcher yaml = YAML_CONTEXT.matcher(content);
      while (yaml.find()) {
        registerContextPath(
            workspaceRoot,
            candidate.path(),
            yaml.group(1),
            lineAt(content, yaml.start(1)),
            configuredPaths);
      }
    }
    if (configuredPaths.size() > 1) {
      throw new SurfaceExtractionException(
          "ambiguous server.servlet.context-path values for module "
              + relative(workspaceRoot, moduleRoot)
              + ": "
              + configuredPaths.keySet());
    }
    String value = configuredPaths.keySet().stream().findFirst().orElse("/");
    return new ContextPath(value, List.copyOf(configuredPaths.values()));
  }

  private static ContextPath findContextPath(
      Path workspaceRoot, Path moduleRoot, Map<String, String> sourceSnapshot) {
    Map<String, ConfigurationSource> configuredPaths = new LinkedHashMap<>();
    sourceSnapshot.entrySet().stream()
        .filter(entry -> isApplicationConfiguration(Path.of(entry.getKey())))
        .filter(entry -> isProductionRelativePath(entry.getKey()))
        .filter(
            entry ->
                moduleRoot.equals(
                    resourceModuleRoot(workspaceRoot, workspaceRoot.resolve(entry.getKey()))))
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              String content = entry.getValue();
              Matcher properties = PROPERTIES_CONTEXT.matcher(content);
              while (properties.find()) {
                registerContextPath(
                    workspaceRoot,
                    workspaceRoot.resolve(entry.getKey()),
                    properties.group(1),
                    lineAt(content, properties.start()),
                    configuredPaths);
              }
              Matcher yaml = YAML_CONTEXT.matcher(content);
              while (yaml.find()) {
                registerContextPath(
                    workspaceRoot,
                    workspaceRoot.resolve(entry.getKey()),
                    yaml.group(1),
                    lineAt(content, yaml.start(1)),
                    configuredPaths);
              }
            });
    if (configuredPaths.size() > 1) {
      throw new SurfaceExtractionException(
          "ambiguous server.servlet.context-path values for module "
              + relative(workspaceRoot, moduleRoot)
              + ": "
              + configuredPaths.keySet());
    }
    String value = configuredPaths.keySet().stream().findFirst().orElse("/");
    return new ContextPath(value, List.copyOf(configuredPaths.values()));
  }

  private static Path resourceModuleRoot(Path workspaceRoot, Path configuration) {
    Path relative = workspaceRoot.relativize(configuration);
    for (int i = 0; i + 2 < relative.getNameCount(); i++) {
      if ("src".equals(relative.getName(i).toString())
          && "main".equals(relative.getName(i + 1).toString())
          && "resources".equals(relative.getName(i + 2).toString())) {
        return i == 0 ? workspaceRoot : workspaceRoot.resolve(relative.subpath(0, i));
      }
    }
    return workspaceRoot;
  }

  private static void registerContextPath(
      Path root,
      Path file,
      String raw,
      int line,
      Map<String, ConfigurationSource> configuredPaths) {
    String value = unquote(raw.strip());
    if (value.isBlank() || value.contains("${") || value.contains("#{")) {
      throw new SurfaceExtractionException(
          "dynamic or empty server.servlet.context-path in " + relative(root, file));
    }
    String canonical = normalizePath(value);
    configuredPaths.putIfAbsent(
        canonical,
        new ConfigurationSource(
            relative(root, file) + ":" + line, "server.servlet.context-path", canonical));
  }

  private static int lineAt(String content, int offset) {
    int line = 1;
    for (int i = 0; i < Math.min(offset, content.length()); i++) {
      if (content.charAt(i) == '\n') line++;
    }
    return line;
  }

  private static boolean isProductionRelativePath(String rawPath) {
    return SpringContractSourceInventory.isProductionRelativePath(rawPath);
  }

  private static boolean isApplicationConfiguration(Path path) {
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.startsWith("application")
        && (name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml"));
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

  private static String relative(Path root, Path path) {
    if (root.equals(path)) return ".";
    return root.relativize(path).toString().replace('\\', '/');
  }

  private static SurfaceExtractionException unsupported(
      Path root, Path file, AnnotationExpr annotation, String reason) {
    int line = annotation.getBegin().map(position -> position.line).orElse(1);
    return new SurfaceExtractionException(reason + " at " + relative(root, file) + ":" + line);
  }

  /**
   * A materialized-workspace extraction result plus its stack-applicability evidence.
   *
   * <p>{@code springMvcMappingSourcePresent} is true only when at least one admitted production
   * Java source declares a Spring MVC mapping annotation this extractor recognizes
   * ({@code @RequestMapping} / {@code @GetMapping} family). Micronaut/JAX-RS/plain-Java sources and
   * non-Java stacks report {@code false}, keeping an empty snapshot honest as "not applicable"
   * rather than "no routes exist".
   */
  public record WorkspaceExtraction(
      SpringHttpSurfaceSnapshot snapshot, boolean springMvcMappingSourcePresent) {}

  private record ParsedSource(Path path, CompilationUnit compilationUnit) {}

  private record ContextPath(String value, List<ConfigurationSource> sources) {}

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

  public static final class SurfaceExtractionException extends RuntimeException {
    public SurfaceExtractionException(String message) {
      super(message);
    }

    public SurfaceExtractionException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
