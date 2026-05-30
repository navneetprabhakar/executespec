package com.navneet.executespec.arch;

import static org.junit.jupiter.api.Assertions.fail;

import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
 * Defensive contract test: every URL referenced from the frontend {@code lib/api.ts} client must
 * resolve to a registered backend route.
 *
 * <p>Catches the failure mode the assessment originally flagged: a frontend call site silently 404s
 * because the backend endpoint was renamed, removed, or never existed (path-typo).
 *
 * <p>The test scans the classpath for {@code @Controller} / {@code @RestController} types, walks
 * their {@code @*Mapping} methods, builds the full URL pattern set, and matches each api.ts URL
 * template against it (placeholders normalised: {@code {runId}} == {@code {id}} == {@code ${runId}}
 * after normalisation).
 *
 * <p>If a legitimate frontend URL is unmatchable (built dynamically, or intentionally points at a
 * not-yet-implemented endpoint), add it to {@link #ALLOWLIST} with a short justifying comment.
 */
class ApiContractTest {

  // api.ts was split into lib/api/*.ts (god-file split, audit 2026-05-29); scan the whole dir.
  private static final Path API_DIR = Paths.get("frontend/src/lib/api");

  /**
   * URL templates intentionally not enforced by this test. Each entry must carry a one-line
   * justification explaining why. Prefer fixing the mismatch over adding to this list.
   */
  private static final Set<String> ALLOWLIST =
      Set.of(
          // (empty — populate as drift surfaces)
          );

  @Test
  void everyApiTsUrlResolvesToRegisteredBackendRoute() throws Exception {
    Set<String> backendPatterns = collectBackendPatterns();
    if (backendPatterns.isEmpty()) {
      fail("Classpath scan found 0 backend routes; the test scaffolding is broken.");
    }

    Set<String> backendNormalised =
        backendPatterns.stream()
            .map(ApiContractTest::normalisePlaceholders)
            .collect(Collectors.toSet());

    if (!Files.isDirectory(API_DIR)) {
      fail(
          "Could not find "
              + API_DIR.toAbsolutePath()
              + " — run this test from the project root (mvn handles this).");
    }

    String apiSource;
    try (var paths = Files.list(API_DIR)) {
      apiSource =
          paths
              .filter(p -> p.toString().endsWith(".ts"))
              .map(
                  p -> {
                    try {
                      return Files.readString(p);
                    } catch (Exception e) {
                      return "";
                    }
                  })
              .collect(Collectors.joining("\n"));
    }

    Set<String> apiTsUrls = extractApiTsUrls(apiSource);
    if (apiTsUrls.isEmpty()) {
      fail("Extracted 0 URLs from lib/api/*.ts; the regex is broken.");
    }

    SortedSet<String> unresolved = new TreeSet<>();
    for (String url : apiTsUrls) {
      if (ALLOWLIST.contains(url)) continue;
      String normalised =
          normalisePlaceholders(prefixApi(stripTrailingConditional(stripQuery(url))));
      if (!backendNormalised.contains(normalised)) {
        unresolved.add(url);
      }
    }

    if (!unresolved.isEmpty()) {
      String sample = unresolved.stream().limit(30).collect(Collectors.joining("\n  "));
      fail(
          "URLs in frontend/src/lib/api.ts with no matching backend route ("
              + unresolved.size()
              + " total, showing up to 30):\n  "
              + sample
              + "\n\nResolution: either (a) add the missing @*Mapping on a controller, "
              + "(b) remove the dead frontend call site in lib/api.ts, or "
              + "(c) add the URL template to ALLOWLIST in "
              + ApiContractTest.class.getSimpleName()
              + " with a one-line justification.\n"
              + "Backend pattern set size = "
              + backendPatterns.size()
              + ".");
    }
  }

  private static final List<Class<? extends Annotation>> METHOD_MAPPING_ANNOTATIONS =
      List.of(
          RequestMapping.class,
          GetMapping.class,
          PostMapping.class,
          PutMapping.class,
          DeleteMapping.class,
          PatchMapping.class);

  /**
   * Bytecode-level scan via ArchUnit (NOT Spring's bean-aware scanner) so
   * {@code @ConditionalOnProperty} controllers are still picked up — they exist as classes
   * regardless of runtime activation.
   */
  private static Set<String> collectBackendPatterns() {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.navneet.executespec");

    Set<String> out = new HashSet<>();
    for (JavaClass clazz : classes) {
      if (!clazz.isAnnotatedWith(RestController.class) && !clazz.isAnnotatedWith(Controller.class))
        continue;

      String[] classPrefixes = extractMappingPaths(clazz);
      if (classPrefixes.length == 0) classPrefixes = new String[] {""};

      for (JavaMethod m : clazz.getMethods()) {
        String[] methodPaths = extractMappingPaths(m);
        if (methodPaths.length == 0) continue;
        for (String prefix : classPrefixes) {
          for (String mp : methodPaths) {
            out.add(combine(prefix, mp));
          }
        }
      }
    }
    return out;
  }

  /** Reads {@code value()}/{@code path()} from the first matching mapping annotation. */
  private static String[] extractMappingPaths(JavaClass clazz) {
    for (Class<? extends Annotation> annType : METHOD_MAPPING_ANNOTATIONS) {
      if (!clazz.isAnnotatedWith(annType)) continue;
      JavaAnnotation<?> a = clazz.getAnnotationOfType(annType.getName());
      return readPaths(a);
    }
    return new String[0];
  }

  private static String[] extractMappingPaths(JavaMethod method) {
    for (Class<? extends Annotation> annType : METHOD_MAPPING_ANNOTATIONS) {
      if (!method.isAnnotatedWith(annType)) continue;
      JavaAnnotation<?> a = method.getAnnotationOfType(annType.getName());
      return readPaths(a);
    }
    return new String[0];
  }

  private static String[] readPaths(JavaAnnotation<?> a) {
    String[] value = readStringArray(a, "value");
    if (value.length > 0) return value;
    String[] path = readStringArray(a, "path");
    if (path.length > 0) return path;
    return new String[] {""};
  }

  private static String[] readStringArray(JavaAnnotation<?> a, String prop) {
    Object raw = a.getProperties().get(prop);
    if (raw instanceof String[] arr) return arr;
    if (raw instanceof Object[] arr) {
      String[] out = new String[arr.length];
      for (int i = 0; i < arr.length; i++) out[i] = String.valueOf(arr[i]);
      return out;
    }
    if (raw instanceof String s) return new String[] {s};
    return new String[0];
  }

  private static String combine(String prefix, String suffix) {
    if (prefix.isEmpty()) return suffix;
    if (suffix.isEmpty()) return prefix;
    boolean prefSlash = prefix.endsWith("/");
    boolean sufSlash = suffix.startsWith("/");
    if (prefSlash && sufSlash) return prefix + suffix.substring(1);
    if (!prefSlash && !sufSlash) return prefix + "/" + suffix;
    return prefix + suffix;
  }

  /**
   * Pulls URL templates from {@code req(...)}, {@code reqText(...)}, and {@code fetch(...)} call
   * sites whose first string-literal argument starts with {@code /}. Dynamic constructions ({@code
   * req(BASE + path)}) are intentionally skipped.
   *
   * <p>Template-literal substitutions ({@code ${...}}) are pre-processed to {@code {x}} via
   * depth-aware brace matching so nested template literals inside ternaries (e.g. {@code `${cond ?
   * `?x=${y}` : ''}`}) don't break the simple quote-bounded URL regex.
   */
  static Set<String> extractApiTsUrls(String content) {
    String neutralised = neutraliseTemplateSubstitutions(content);
    Set<String> urls = new HashSet<>();
    Pattern p =
        Pattern.compile(
            "(?<![\\w$.])(req|reqText|fetch)\\s*(?:<[^>]+>)?\\s*\\(\\s*([\"'`])(/[^\"'`\\n]*)\\2");
    Matcher m = p.matcher(neutralised);
    while (m.find()) {
      urls.add(m.group(3));
    }
    return urls;
  }

  /**
   * Replaces every {@code ${...}} (with depth-counted braces) by a single {@code {x}} placeholder.
   * Survives nested template literals and ternaries.
   */
  static String neutraliseTemplateSubstitutions(String content) {
    StringBuilder out = new StringBuilder(content.length());
    int i = 0;
    while (i < content.length()) {
      if (i + 1 < content.length() && content.charAt(i) == '$' && content.charAt(i + 1) == '{') {
        int depth = 1;
        int j = i + 2;
        while (j < content.length() && depth > 0) {
          char c = content.charAt(j);
          if (c == '{') depth++;
          else if (c == '}') depth--;
          if (depth == 0) break;
          j++;
        }
        out.append("{x}");
        i = j + 1;
      } else {
        out.append(content.charAt(i));
        i++;
      }
    }
    return out.toString();
  }

  private static String stripQuery(String url) {
    int q = url.indexOf('?');
    return q >= 0 ? url.substring(0, q) : url;
  }

  /**
   * If a URL ends with {@code {x}} that is NOT a path segment (no preceding slash), drop it. That
   * trailing substitution almost always represents an optional query string, e.g. {@code
   * `/foo${cond ? '?bar=...' : ''}`} → {@code /foo{x}} → {@code /foo}.
   */
  private static String stripTrailingConditional(String url) {
    if (url.endsWith("{x}") && !url.endsWith("/{x}")) {
      return url.substring(0, url.length() - 3);
    }
    return url;
  }

  private static String prefixApi(String url) {
    return url.startsWith("/api/") || url.equals("/api") ? url : "/api" + url;
  }

  /**
   * Replaces {@code ${var}} (TS template literal) and {@code {var}} (Spring path variable,
   * optionally with regex constraint) with a canonical {@code {}} so structurally-identical paths
   * compare equal regardless of placeholder name.
   */
  static String normalisePlaceholders(String pattern) {
    String s = pattern.replaceAll("\\$\\{[^}]*\\}", "{x}");
    return s.replaceAll("\\{[^/}]*\\}", "{}");
  }
}
