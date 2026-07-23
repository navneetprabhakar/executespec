package com.navneet.executespec.arch;

import static org.junit.jupiter.api.Assertions.fail;

import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the authoritative backend HTTP route inventory as a committed artifact so the docs
 * repository can perform OpenAPI drift detection without compiling Java.
 *
 * <p>Why an artifact instead of a direct spec comparison here: the backend CI checkout does not
 * contain {@code executespec-docs}, so a server-side assertion against the OpenAPI file would
 * either be skipped (useless) or break CI. Instead this test owns route ENUMERATION (accurate,
 * bytecode level) and fails when the committed inventory drifts from the compiled controllers; the
 * docs test {@code tests/test_openapi_route_coverage.py} owns the COMPARISON against {@code
 * backend/reference/openapi.yaml}.
 *
 * <p>Regenerate after adding or renaming a route:
 *
 * <pre>
 *   mvn -Dtest=ApiRouteInventoryTest -Dapi.route.inventory.write=true test
 * </pre>
 */
class ApiRouteInventoryTest {

  static final Path INVENTORY = Paths.get("src/test/resources/arch/api-route-inventory.json");

  private static final String WRITE_PROPERTY = "api.route.inventory.write";

  private static final List<Class<? extends Annotation>> MAPPING_ANNOTATIONS =
      List.of(
          RequestMapping.class,
          GetMapping.class,
          PostMapping.class,
          PutMapping.class,
          DeleteMapping.class,
          PatchMapping.class);

  private static final Map<Class<? extends Annotation>, String> IMPLIED_METHOD =
      Map.of(
          GetMapping.class, "GET",
          PostMapping.class, "POST",
          PutMapping.class, "PUT",
          DeleteMapping.class, "DELETE",
          PatchMapping.class, "PATCH");

  @Test
  void committedRouteInventoryMatchesCompiledControllers() throws Exception {
    SortedSet<String> actual = collectRoutes();
    if (actual.isEmpty()) {
      fail("Classpath scan found 0 backend routes; the test scaffolding is broken.");
    }

    if (Boolean.getBoolean(WRITE_PROPERTY)) {
      writeInventory(actual);
      return;
    }

    if (!Files.isRegularFile(INVENTORY)) {
      fail(
          "Missing route inventory "
              + INVENTORY
              + ". Regenerate with -D"
              + WRITE_PROPERTY
              + "=true.");
    }

    SortedSet<String> committed = readInventory();
    SortedSet<String> added = new TreeSet<>(actual);
    added.removeAll(committed);
    SortedSet<String> removed = new TreeSet<>(committed);
    removed.removeAll(actual);

    if (!added.isEmpty() || !removed.isEmpty()) {
      fail(
          "Backend route inventory is stale ("
              + added.size()
              + " added, "
              + removed.size()
              + " removed).\nAdded:\n  "
              + join(added)
              + "\nRemoved:\n  "
              + join(removed)
              + "\n\nRegenerate with: mvn -Dtest="
              + ApiRouteInventoryTest.class.getSimpleName()
              + " -D"
              + WRITE_PROPERTY
              + "=true test\n"
              + "Then document any NEW route in executespec-docs/backend/reference/openapi.yaml,"
              + " because tests/test_openapi_route_coverage.py enforces coverage.");
    }
  }

  private static String join(SortedSet<String> values) {
    return values.isEmpty()
        ? "(none)"
        : values.stream().limit(40).collect(Collectors.joining("\n  "));
  }

  /** Each entry is {@code "<METHOD> <full path>"}; {@code ANY} means no method restriction. */
  static SortedSet<String> collectRoutes() {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.navneet.executespec");

    SortedSet<String> routes = new TreeSet<>();
    for (JavaClass clazz : classes) {
      if (!clazz.isAnnotatedWith(RestController.class)
          && !clazz.isAnnotatedWith(Controller.class)) {
        continue;
      }
      String[] prefixes = classPaths(clazz);
      for (JavaMethod method : clazz.getMethods()) {
        addMethodRoutes(routes, prefixes, method);
      }
    }
    return routes;
  }

  private static void addMethodRoutes(SortedSet<String> routes, String[] prefixes, JavaMethod m) {
    for (Class<? extends Annotation> annType : MAPPING_ANNOTATIONS) {
      if (!m.isAnnotatedWith(annType)) continue;
      JavaAnnotation<?> annotation = m.getAnnotationOfType(annType.getName());
      List<String> verbs = httpMethods(annType, annotation);
      for (String suffix : readPaths(annotation)) {
        for (String prefix : prefixes) {
          String path = normalisePath(combine(prefix, suffix));
          for (String verb : verbs) {
            routes.add(verb + " " + path);
          }
        }
      }
      return;
    }
  }

  private static String[] classPaths(JavaClass clazz) {
    for (Class<? extends Annotation> annType : MAPPING_ANNOTATIONS) {
      if (!clazz.isAnnotatedWith(annType)) continue;
      return readPaths(clazz.getAnnotationOfType(annType.getName()));
    }
    return new String[] {""};
  }

  private static List<String> httpMethods(
      Class<? extends Annotation> annType, JavaAnnotation<?> annotation) {
    String implied = IMPLIED_METHOD.get(annType);
    if (implied != null) return List.of(implied);
    List<String> declared = new ArrayList<>();
    Object raw = annotation.getProperties().get("method");
    if (raw instanceof Object[] values) {
      for (Object value : values) {
        String text = String.valueOf(value);
        declared.add(text.substring(text.lastIndexOf('.') + 1));
      }
    } else if (raw != null) {
      String text = String.valueOf(raw);
      declared.add(text.substring(text.lastIndexOf('.') + 1));
    }
    return declared.isEmpty() ? List.of("ANY") : declared;
  }

  private static String[] readPaths(JavaAnnotation<?> annotation) {
    String[] value = readStringArray(annotation, "value");
    if (value.length > 0) return value;
    String[] path = readStringArray(annotation, "path");
    if (path.length > 0) return path;
    return new String[] {""};
  }

  private static String[] readStringArray(JavaAnnotation<?> annotation, String property) {
    Object raw = annotation.getProperties().get(property);
    if (raw instanceof String[] array) return array;
    if (raw instanceof Object[] array) {
      String[] out = new String[array.length];
      for (int i = 0; i < array.length; i++) out[i] = String.valueOf(array[i]);
      return out;
    }
    if (raw instanceof String single) return new String[] {single};
    return new String[0];
  }

  private static String combine(String prefix, String suffix) {
    if (prefix.isEmpty()) return suffix;
    if (suffix.isEmpty()) return prefix;
    boolean prefixSlash = prefix.endsWith("/");
    boolean suffixSlash = suffix.startsWith("/");
    if (prefixSlash && suffixSlash) return prefix + suffix.substring(1);
    if (!prefixSlash && !suffixSlash) return prefix + "/" + suffix;
    return prefix + suffix;
  }

  /** Drops Spring regex constraints ({@code {id:[0-9]+}}) and any trailing slash. */
  private static String normalisePath(String path) {
    String normalised = path.replaceAll("\\{([^:/}]+):[^}]*\\}", "{$1}");
    if (normalised.length() > 1 && normalised.endsWith("/")) {
      normalised = normalised.substring(0, normalised.length() - 1);
    }
    return normalised.startsWith("/") ? normalised : "/" + normalised;
  }

  private static void writeInventory(SortedSet<String> routes) throws Exception {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("generator", ApiRouteInventoryTest.class.getName());
    document.put(
        "description",
        "Authoritative backend HTTP route inventory. Regenerate with -D"
            + WRITE_PROPERTY
            + "=true. Consumed by executespec-docs tests/test_openapi_route_coverage.py.");
    document.put("routeCount", routes.size());
    document.put("routes", List.copyOf(routes));
    Files.createDirectories(INVENTORY.getParent());
    Files.writeString(
        INVENTORY,
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n");
  }

  private static SortedSet<String> readInventory() throws Exception {
    JsonNode root = new ObjectMapper().readTree(Files.readString(INVENTORY));
    SortedSet<String> routes = new TreeSet<>();
    root.path("routes").forEach(node -> routes.add(node.asText()));
    return routes;
  }
}
