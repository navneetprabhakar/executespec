package com.navneet.executespec.codeintel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.navneet.executespec.contractsurface.SpringHttpSurfaceExtractor.Route;
import com.navneet.executespec.contractsurface.SpringHttpSurfaceSnapshot;
import com.navneet.executespec.repo.GitHubRepoService;

/**
 * Renders a bounded, exact-revision evidence appendix for facts declaration inventory cannot prove.
 *
 * <p>This is deliberately not model prose. It records selected manifest/configuration paths,
 * Compose service declarations, schema-bootstrap configuration, and Spring handler response mode.
 * Negative claims are emitted only when the repository inventory and every selected evidence read
 * are complete.
 */
final class DeterministicProjectFactEvidence {

  static final String BEGIN = "<!-- EXECUTESPEC_DETERMINISTIC_PROJECT_FACTS_V1_BEGIN -->";
  static final String END = "<!-- EXECUTESPEC_DETERMINISTIC_PROJECT_FACTS_V1_END -->";
  private static final int MAX_AUXILIARY_READS = 8;
  private static final Pattern PROPERTY =
      Pattern.compile(
          "(?m)^\\s*(spring\\.(?:jpa\\.hibernate\\.ddl-auto|sql\\.init\\.schema-locations|"
              + "flyway\\.[A-Za-z0-9_.-]+|liquibase\\.[A-Za-z0-9_.-]+))\\s*[=:]\\s*([^#\\r\\n]*)");
  private static final Set<String> MANIFEST_NAMES =
      Set.of(
          "pom.xml",
          "build.gradle",
          "build.gradle.kts",
          "settings.gradle",
          "settings.gradle.kts",
          "package.json",
          "pyproject.toml",
          "requirements.txt",
          "go.mod",
          "cargo.toml");

  private DeterministicProjectFactEvidence() {}

  static String render(
      String repoFullName,
      String sourceRevision,
      List<String> files,
      String pat,
      boolean inventoryTruncated,
      GitHubRepoService github,
      Map<String, String> springSnapshot,
      SpringHttpSurfaceSnapshot httpSurface) {
    List<String> orderedFiles =
        files == null
            ? List.of()
            : files.stream().filter(java.util.Objects::nonNull).sorted().toList();
    Map<String, String> auxiliary = new LinkedHashMap<>();
    boolean readsComplete = true;
    List<String> selected =
        orderedFiles.stream()
            .filter(DeterministicProjectFactEvidence::isAuxiliaryEvidencePath)
            .limit(MAX_AUXILIARY_READS)
            .toList();
    boolean selectedTruncated =
        orderedFiles.stream()
                .filter(DeterministicProjectFactEvidence::isAuxiliaryEvidencePath)
                .count()
            > MAX_AUXILIARY_READS;
    for (String path : selected) {
      try {
        GitHubRepoService.RepoFileResult read =
            github.readRepoFile("https://github.com/" + repoFullName, path, sourceRevision, pat);
        if (read == null || !read.successful()) {
          readsComplete = false;
        } else {
          auxiliary.put(path, read.content());
        }
      } catch (Exception failure) {
        readsComplete = false;
      }
    }

    boolean immutableRevision = sourceRevision != null && sourceRevision.matches("[0-9a-fA-F]{40}");
    boolean complete =
        immutableRevision && !inventoryTruncated && !selectedTruncated && readsComplete;
    StringBuilder out = new StringBuilder();
    out.append(BEGIN)
        .append("\n## Deterministic project-document facts\n\n")
        .append("- Repository: `")
        .append(repoFullName)
        .append("`\n")
        .append("- Source revision: `")
        .append(sourceRevision)
        .append("`\n- Status: `")
        .append(complete ? "AVAILABLE" : "PARTIAL")
        .append("`\n")
        .append(
            "- Authority: exact-revision repository evidence; model-generated architecture prose"
                + " is not grounding evidence.\n\n");

    renderFiles(out, orderedFiles);
    Map<String, String> allConfigurations = new LinkedHashMap<>();
    if (springSnapshot != null) allConfigurations.putAll(springSnapshot);
    allConfigurations.putAll(auxiliary);
    renderSchemaEvidence(out, orderedFiles, allConfigurations);
    renderComposeEvidence(out, auxiliary);
    renderHandlerEvidence(out, springSnapshot, httpSurface);
    out.append('\n').append(END).append("\n\n");
    return out.toString();
  }

  private static void renderFiles(StringBuilder out, List<String> files) {
    List<String> manifests =
        files.stream().filter(DeterministicProjectFactEvidence::isManifest).toList();
    out.append("### Build manifests\n\n");
    if (manifests.isEmpty()) {
      out.append("- No selected build manifest path was discovered.\n");
    } else {
      manifests.forEach(path -> out.append("- `").append(path).append("`\n"));
    }
  }

  private static void renderSchemaEvidence(
      StringBuilder out, List<String> files, Map<String, String> configurations) {
    out.append("\n### Schema and migration evidence\n\n");
    List<String> schemaPaths =
        files.stream()
            .filter(DeterministicProjectFactEvidence::isSchemaOrMigrationPath)
            .limit(40)
            .toList();
    if (schemaPaths.isEmpty())
      out.append("- No selected schema or migration path was discovered.\n");
    else schemaPaths.forEach(path -> out.append("- Path: `").append(path).append("`\n"));

    boolean propertyFound = false;
    for (Map.Entry<String, String> entry : configurations.entrySet()) {
      if (!isApplicationConfiguration(entry.getKey()) || entry.getValue() == null) continue;
      Matcher matcher = PROPERTY.matcher(entry.getValue());
      while (matcher.find()) {
        propertyFound = true;
        out.append("- Property: `")
            .append(matcher.group(1))
            .append('=')
            .append(matcher.group(2).trim())
            .append("` — `")
            .append(entry.getKey())
            .append(':')
            .append(lineAt(entry.getValue(), matcher.start()))
            .append("`\n");
      }
    }
    if (!propertyFound) out.append("- No selected schema-management property was discovered.\n");
  }

  private static void renderComposeEvidence(StringBuilder out, Map<String, String> auxiliary) {
    out.append("\n### Compose service declarations\n\n");
    List<ComposeService> services = new ArrayList<>();
    auxiliary.forEach(
        (path, content) -> {
          if (isComposePath(path)) services.addAll(parseCompose(path, content));
        });
    if (services.isEmpty()) {
      out.append("- No readable Compose service declaration was discovered.\n");
      out.append("- Application service evidence: `NOT_ESTABLISHED`\n");
      return;
    }
    services.forEach(
        service ->
            out.append("- Service: `")
                .append(service.name())
                .append("` — source `")
                .append(service.path())
                .append("`; image `")
                .append(service.image() == null ? "NOT_DECLARED" : service.image())
                .append("`; build `")
                .append(service.buildDeclared() ? "DECLARED" : "NOT_DECLARED")
                .append("`\n"));
    // A Compose declaration proves service configuration, not the semantic role of an arbitrary
    // image/build target and certainly not that the complete application starts successfully.
    // Keep the inventory exact and make role/startup claims explicitly non-authoritative.
    out.append("- Application service evidence: `NOT_CLASSIFIED`\n");
  }

  private static void renderHandlerEvidence(
      StringBuilder out,
      Map<String, String> springSnapshot,
      SpringHttpSurfaceSnapshot httpSurface) {
    out.append("\n### Spring route handler response evidence\n\n");
    if (springSnapshot == null || httpSurface == null || httpSurface.routes().isEmpty()) {
      out.append("- No exact Spring handler response evidence was discovered.\n");
      return;
    }
    Map<String, HandlerFact> handlers = handlers(springSnapshot);
    for (Route route : httpSurface.routes()) {
      HandlerFact fact = handlers.get(route.source());
      if (fact == null) {
        out.append("- `")
            .append(route.method())
            .append(' ')
            .append(route.path())
            .append("` — response mode `NOT_ESTABLISHED`; source `")
            .append(route.source())
            .append("`\n");
      } else {
        out.append("- `")
            .append(route.method())
            .append(' ')
            .append(route.path())
            .append("` — handler `")
            .append(fact.method())
            .append("`; return type `")
            .append(fact.returnType())
            .append("`; response mode `")
            .append(fact.responseMode())
            .append("`; source `")
            .append(route.source())
            .append("`\n");
      }
    }
  }

  private static Map<String, HandlerFact> handlers(Map<String, String> snapshot) {
    Map<String, HandlerFact> facts = new LinkedHashMap<>();
    ParserConfiguration configuration =
        new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    snapshot.entrySet().stream()
        .filter(entry -> entry.getKey().endsWith(".java"))
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              var parsed = new JavaParser(configuration).parse(entry.getValue());
              if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) return;
              parsed
                  .getResult()
                  .orElseThrow()
                  .findAll(ClassOrInterfaceDeclaration.class)
                  .forEach(
                      type -> {
                        boolean bodyType =
                            hasAnnotation(type.getAnnotations(), "RestController")
                                || hasAnnotation(type.getAnnotations(), "ResponseBody");
                        for (MethodDeclaration method : type.getMethods()) {
                          AnnotationExpr mapping =
                              method.getAnnotations().stream()
                                  .filter(DeterministicProjectFactEvidence::isMapping)
                                  .findFirst()
                                  .orElse(null);
                          if (mapping == null) continue;
                          int line = mapping.getBegin().map(position -> position.line).orElse(1);
                          boolean responseBody =
                              bodyType
                                  || hasAnnotation(method.getAnnotations(), "ResponseBody")
                                  || hasAnnotation(
                                      method.getType().getAnnotations(), "ResponseBody")
                                  || (method.getType().isClassOrInterfaceType()
                                      && "ResponseEntity"
                                          .equals(
                                              method
                                                  .getType()
                                                  .asClassOrInterfaceType()
                                                  .getNameAsString()));
                          String mode =
                              responseBody
                                  ? "SERIALIZED_BODY"
                                  : "String".equals(method.getType().asString())
                                      ? "VIEW_NAME"
                                      : "NOT_ESTABLISHED";
                          facts.put(
                              entry.getKey() + ":" + line,
                              new HandlerFact(
                                  method.getNameAsString(), method.getType().asString(), mode));
                        }
                      });
            });
    return facts;
  }

  private static List<ComposeService> parseCompose(String path, String content) {
    if (content == null || content.isBlank()) return List.of();
    List<ComposeService> services = new ArrayList<>();
    boolean inServices = false;
    String name = null;
    String image = null;
    boolean build = false;
    for (String line : content.split("\\R")) {
      String stripped = line.stripLeading();
      if (stripped.isBlank() || stripped.startsWith("#")) continue;
      int indent = line.length() - stripped.length();
      if (indent == 0) {
        if (name != null) services.add(new ComposeService(path, name, image, build));
        name = null;
        image = null;
        build = false;
        inServices = "services:".equals(stripped);
        continue;
      }
      if (!inServices) continue;
      if (indent == 2 && stripped.matches("[A-Za-z0-9_.-]+:\\s*(?:#.*)?")) {
        if (name != null) services.add(new ComposeService(path, name, image, build));
        name = stripped.substring(0, stripped.indexOf(':'));
        image = null;
        build = false;
      } else if (name != null && indent >= 4 && stripped.startsWith("image:")) {
        image = stripYamlScalar(stripped.substring("image:".length()));
      } else if (name != null && indent >= 4 && stripped.startsWith("build:")) {
        build = true;
      }
    }
    if (name != null) services.add(new ComposeService(path, name, image, build));
    return List.copyOf(services);
  }

  private static boolean isMapping(AnnotationExpr annotation) {
    String name = annotation.getName().getIdentifier();
    return "RequestMapping".equals(name) || name.matches("(?:Get|Post|Put|Patch|Delete)Mapping");
  }

  private static boolean hasAnnotation(List<AnnotationExpr> annotations, String simpleName) {
    return annotations.stream()
        .anyMatch(annotation -> simpleName.equals(annotation.getName().getIdentifier()));
  }

  private static boolean isAuxiliaryEvidencePath(String path) {
    return isComposePath(path) || isApplicationConfiguration(path);
  }

  private static boolean isComposePath(String path) {
    String name = Path.of(path).getFileName().toString().toLowerCase(Locale.ROOT);
    return name.matches("(?:docker-)?compose(?:[.-][a-z0-9_.-]+)?\\.ya?ml")
        || name.matches("docker-compose(?:[.-][a-z0-9_.-]+)?\\.ya?ml");
  }

  private static boolean isApplicationConfiguration(String path) {
    String name = Path.of(path).getFileName().toString().toLowerCase(Locale.ROOT);
    return name.startsWith("application")
        && (name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml"));
  }

  private static boolean isManifest(String path) {
    return MANIFEST_NAMES.contains(Path.of(path).getFileName().toString().toLowerCase(Locale.ROOT));
  }

  private static boolean isSchemaOrMigrationPath(String path) {
    String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
    String name = Path.of(path).getFileName().toString().toLowerCase(Locale.ROOT);
    return name.equals("schema.sql")
        || normalized.contains("/db/migration/")
        || normalized.contains("/db/changelog/")
        || normalized.contains("/migrations/");
  }

  private static int lineAt(String content, int offset) {
    int line = 1;
    for (int i = 0; i < Math.min(offset, content.length()); i++)
      if (content.charAt(i) == '\n') line++;
    return line;
  }

  private static String stripYamlScalar(String value) {
    String result = value == null ? "" : value.trim();
    int comment = result.indexOf(" #");
    if (comment >= 0) result = result.substring(0, comment).trim();
    if (result.length() >= 2
        && ((result.startsWith("\"") && result.endsWith("\""))
            || (result.startsWith("'") && result.endsWith("'")))) {
      result = result.substring(1, result.length() - 1);
    }
    return result;
  }

  private record ComposeService(String path, String name, String image, boolean buildDeclared) {}

  private record HandlerFact(String method, String returnType, String responseMode) {}
}
