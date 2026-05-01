package com.navneet.executespec.project.service;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.repo.GitHubRepoService;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Heuristic stack detection for {@link com.navneet.executespec.project.entity.ProjectRepo}. Lists
 * the files at the repo root via the GitHub API and infers {@code (language, framework, version)}
 * from well-known marker files. The result is serialized to JSON for persistence in {@code
 * ProjectRepo.detectedStack}.
 *
 * <p>No shallow clone required — cheap enough to run on every {@code POST /repos/{id}/sync}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StackDetector {

  private final GitHubRepoService gitHub;
  private final ObjectMapper json = new ObjectMapper();

  /**
   * @param fullName {@code owner/repo}
   * @param pat optional per-user PAT; {@code null} falls back to system PAT
   * @return JSON string {@code {"language":..., "framework":..., "version":...}} or null when the
   *     repo could not be inspected
   */
  public String detect(String fullName, String pat) {
    String repoUrl = "https://github.com/" + fullName;
    List<String> files;
    try {
      files = gitHub.listRepoFiles(repoUrl, pat);
    } catch (Exception e) {
      log.warn("StackDetector list-files failed for {}: {}", fullName, e.getMessage());
      return null;
    }
    if (files == null || files.isEmpty()) return null;

    StackInfo info = infer(files);
    if (info == null) return null;

    // Best-effort version pull from the marker file. One extra GitHub call
    // per detect — acceptable since stack changes rarely.
    String version = info.version();
    if (version == null || version.isBlank()) {
      version = detectVersion(repoUrl, pat, info, files);
    }

    try {
      return json.writeValueAsString(
          Map.of(
              "language", info.language(),
              "framework", info.framework() == null ? "" : info.framework(),
              "version", version == null ? "" : version));
    } catch (Exception e) {
      return null;
    }
  }

  private String detectVersion(String repoUrl, String pat, StackInfo info, List<String> files) {
    try {
      if ("Java".equals(info.language()) && hasRoot(files, "pom.xml")) {
        String pom = gitHub.readRepoFile(repoUrl, "pom.xml", pat);
        if (pom != null) {
          java.util.regex.Matcher m =
              java.util.regex.Pattern.compile("<version>([^<]+)</version>").matcher(pom);
          if (m.find()) return m.group(1);
        }
      } else if ("TypeScript/JS".equals(info.language()) && hasRoot(files, "package.json")) {
        String pkg = gitHub.readRepoFile(repoUrl, "package.json", pat);
        if (pkg != null) {
          java.util.regex.Matcher m =
              java.util.regex.Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(pkg);
          if (m.find()) return m.group(1);
        }
      } else if ("Python".equals(info.language()) && hasRoot(files, "pyproject.toml")) {
        String py = gitHub.readRepoFile(repoUrl, "pyproject.toml", pat);
        if (py != null) {
          java.util.regex.Matcher m =
              java.util.regex.Pattern.compile("(?m)^version\\s*=\\s*\"([^\"]+)\"").matcher(py);
          if (m.find()) return m.group(1);
        }
      }
    } catch (Exception e) {
      log.debug("Version detect failed: {}", e.getMessage());
    }
    return null;
  }

  record StackInfo(String language, String framework, String version) {}

  private static StackInfo infer(List<String> files) {
    boolean hasPom = hasRoot(files, "pom.xml");
    boolean hasGradle = hasRoot(files, "build.gradle") || hasRoot(files, "build.gradle.kts");
    boolean hasPkg = hasRoot(files, "package.json");
    boolean hasReq =
        hasRoot(files, "requirements.txt")
            || hasRoot(files, "pyproject.toml")
            || hasRoot(files, "setup.py");
    boolean hasGoMod = hasRoot(files, "go.mod");
    boolean hasCargo = hasRoot(files, "Cargo.toml");
    boolean hasGemfile = hasRoot(files, "Gemfile");
    boolean hasComposer = hasRoot(files, "composer.json");

    if (hasPom)
      return new StackInfo("Java", hasFile(files, "application.yml") ? "Spring Boot" : "", "");
    if (hasGradle)
      return new StackInfo(
          "Java", hasFile(files, "application.yml") ? "Spring Boot" : "Gradle", "");
    if (hasPkg) {
      String framework = detectNodeFramework(files);
      return new StackInfo("TypeScript/JS", framework, "");
    }
    if (hasReq) {
      String framework = detectPythonFramework(files);
      return new StackInfo("Python", framework, "");
    }
    if (hasGoMod) return new StackInfo("Go", "", "");
    if (hasCargo) return new StackInfo("Rust", "", "");
    if (hasGemfile) return new StackInfo("Ruby", "Rails", "");
    if (hasComposer) return new StackInfo("PHP", "", "");
    return null;
  }

  // Package-private for StackDetectorTest.
  static String detectNodeFramework(List<String> files) {
    // Expo's `app.json` at the repo root is a high-signal marker that this is
    // a managed React Native project (paired with a package.json depending on
    // `expo`). Check BEFORE the generic "react-native" string match so we get
    // the more specific label. Next.js / Vite / NestJS retain the existing
    // `hasFile` semantics (match in any dir) so monorepos still classify them
    // correctly — only Expo uses the stricter root-only check because
    // `app.json` is a common filename in non-Expo contexts.
    if (hasFile(files, "next.config.js") || hasFile(files, "next.config.mjs")) return "Next.js";
    if (hasFile(files, "vite.config.ts") || hasFile(files, "vite.config.js")) return "Vite";
    if (hasFile(files, "nest-cli.json")) return "NestJS";
    if (hasRoot(files, "app.json")) return "Expo (React Native)";
    if (anyContains(files, "react-native")) return "React Native";
    return "Node";
  }

  private static String detectPythonFramework(List<String> files) {
    if (anyContains(files, "fastapi")) return "FastAPI";
    if (anyContains(files, "django")) return "Django";
    if (anyContains(files, "flask")) return "Flask";
    return "";
  }

  private static boolean hasRoot(List<String> files, String name) {
    return files.stream().anyMatch(p -> p.equals(name));
  }

  private static boolean hasFile(List<String> files, String name) {
    return files.stream().anyMatch(p -> p.equals(name) || p.endsWith("/" + name));
  }

  private static boolean anyContains(List<String> files, String needle) {
    return files.stream().anyMatch(p -> p.contains(needle));
  }
}
