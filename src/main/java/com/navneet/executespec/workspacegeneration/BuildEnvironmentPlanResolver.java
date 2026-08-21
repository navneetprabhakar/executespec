package com.navneet.executespec.workspacegeneration;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.sandboxroute.SandboxCommandRoutingService;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Resolves a frozen, offline dependency materialization plan from accepted manifests only. */
@Service
@RequiredArgsConstructor
public class BuildEnvironmentPlanResolver {

  private static final int MAX_DEPTH = 8;
  private static final int MAX_MODULES = 64;
  private static final Set<String> SKIP_DIRECTORIES =
      Set.of(
          ".git",
          "node_modules",
          ".venv",
          "venv",
          "target",
          "build",
          "dist",
          ".gradle",
          ".m2",
          ".cargo",
          "vendor");

  private final ObjectMapper objectMapper;
  private final SandboxCommandRoutingService sandboxRouting;

  public BuildEnvironmentPlan resolve(Path workspaceRoot) {
    Path root = requireRoot(workspaceRoot);
    List<BuildEnvironmentPlan.Module> modules =
        primaryManifests(root).stream()
            .map(path -> module(root, path))
            .filter(java.util.Objects::nonNull)
            .sorted(
                Comparator.comparing(BuildEnvironmentPlan.Module::repositoryRelativeRoot)
                    .thenComparing(BuildEnvironmentPlan.Module::ecosystem))
            .toList();
    if (modules.size() > MAX_MODULES) {
      throw new IllegalStateException("BUILD_ENVIRONMENT_MODULE_LIMIT_EXCEEDED");
    }
    String manifestDigest =
        DigestUtils.sha256Hex(
            modules.stream()
                .map(module -> module.repositoryRelativeRoot() + "=" + module.manifestDigest())
                .reduce("", (left, right) -> left + "\n" + right));
    String runtimeImage = sandboxRouting.runtimeImageIdentity();
    if (runtimeImage == null || !runtimeImage.matches("^.+@sha256:[0-9a-fA-F]{64}$")) {
      throw new IllegalStateException("BUILD_ENVIRONMENT_RUNTIME_IMAGE_NOT_PINNED");
    }
    Map<String, Object> canonical = new LinkedHashMap<>();
    canonical.put("schemaVersion", 1);
    canonical.put("runtimeImage", runtimeImage);
    canonical.put("networkPolicy", "DENY_ALL");
    canonical.put("cachePolicy", "IMAGE_PRESEEDED_OFFLINE_ONLY");
    canonical.put("modules", modules);
    canonical.put("manifestDigest", manifestDigest);
    String planDigest = DigestUtils.sha256Hex(json(canonical));
    return new BuildEnvironmentPlan(
        1,
        runtimeImage,
        "DENY_ALL",
        "IMAGE_PRESEEDED_OFFLINE_ONLY",
        modules,
        manifestDigest,
        planDigest);
  }

  private BuildEnvironmentPlan.Module module(Path root, Path primaryManifest) {
    Path moduleRoot = primaryManifest.getParent();
    String primaryName = primaryManifest.getFileName().toString();
    String ecosystem;
    List<String> argv;
    List<Path> bound;
    int timeout;
    if (primaryName.equals("package.json")) {
      Path lock =
          firstExisting(
              moduleRoot,
              "pnpm-lock.yaml",
              "yarn.lock",
              "package-lock.json",
              "npm-shrinkwrap.json");
      if (lock == null) {
        if (!nodeHasDependencies(primaryManifest)) return null;
        throw new IllegalStateException("BUILD_ENVIRONMENT_NODE_LOCKFILE_REQUIRED");
      }
      ecosystem = "NODE";
      bound = List.of(primaryManifest, lock);
      argv =
          switch (lock.getFileName().toString()) {
            case "pnpm-lock.yaml" ->
                List.of("pnpm", "install", "--frozen-lockfile", "--offline", "--ignore-scripts");
            case "yarn.lock" ->
                List.of("yarn", "install", "--frozen-lockfile", "--offline", "--ignore-scripts");
            default ->
                List.of("npm", "ci", "--offline", "--ignore-scripts", "--no-audit", "--no-fund");
          };
      timeout = 600;
    } else if (primaryName.equals("pom.xml")) {
      ecosystem = "MAVEN";
      bound = existing(moduleRoot, primaryManifest, ".mvn/wrapper/maven-wrapper.properties");
      argv = List.of("mvn", "-o", "-q", "-DskipTests", "dependency:go-offline");
      timeout = 600;
    } else if (primaryName.equals("build.gradle") || primaryName.equals("build.gradle.kts")) {
      ecosystem = "GRADLE";
      bound =
          existing(
              moduleRoot,
              primaryManifest,
              "gradle.lockfile",
              "gradle/wrapper/gradle-wrapper.properties");
      argv =
          Files.isRegularFile(moduleRoot.resolve("gradlew"))
              ? List.of("./gradlew", "--offline", "dependencies", "--quiet")
              : List.of("gradle", "--offline", "dependencies", "--quiet");
      timeout = 600;
    } else if (primaryName.equals("Cargo.toml")) {
      ecosystem = "RUST";
      Path lock = moduleRoot.resolve("Cargo.lock");
      if (!Files.isRegularFile(lock)) {
        throw new IllegalStateException("BUILD_ENVIRONMENT_CARGO_LOCKFILE_REQUIRED");
      }
      bound = List.of(primaryManifest, lock);
      argv = List.of("cargo", "fetch", "--offline", "--locked");
      timeout = 600;
    } else if (primaryName.equals("go.mod")) {
      ecosystem = "GO";
      bound = existing(moduleRoot, primaryManifest, "go.sum");
      argv = List.of("env", "GOPROXY=off", "GOSUMDB=off", "go", "mod", "download");
      timeout = 600;
    } else if (primaryName.equals("requirements.txt")) {
      ecosystem = "PYTHON";
      requireExactPythonPins(primaryManifest);
      bound = existing(moduleRoot, primaryManifest, "pyproject.toml");
      argv = List.of("python3", "-c", pythonEnvironmentValidator(), "requirements.txt");
      timeout = 120;
    } else {
      return null;
    }
    List<String> paths = bound.stream().map(path -> relative(root, path)).sorted().toList();
    String digest = digestFiles(root, paths);
    return new BuildEnvironmentPlan.Module(
        relative(root, moduleRoot), ecosystem, paths, digest, argv, timeout);
  }

  private static List<Path> primaryManifests(Path root) {
    List<Path> found = new ArrayList<>();
    try {
      Files.walkFileTree(
          root,
          Set.of(),
          MAX_DEPTH,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
              if (!dir.equals(root)
                  && (Files.isSymbolicLink(dir)
                      || SKIP_DIRECTORIES.contains(dir.getFileName().toString()))) {
                return FileVisitResult.SKIP_SUBTREE;
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
              if (primaryManifestName(file.getFileName().toString())) found.add(file);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException failure) {
      throw new IllegalStateException("BUILD_ENVIRONMENT_MANIFEST_SCAN_FAILED", failure);
    }
    return found.stream().sorted().toList();
  }

  private static boolean primaryManifestName(String name) {
    return Set.of(
            "package.json",
            "pom.xml",
            "build.gradle",
            "build.gradle.kts",
            "Cargo.toml",
            "go.mod",
            "requirements.txt")
        .contains(name);
  }

  private boolean nodeHasDependencies(Path packageJson) {
    try {
      JsonNode root = objectMapper.readTree(packageJson.toFile());
      return hasFields(root.path("dependencies"))
          || hasFields(root.path("devDependencies"))
          || hasFields(root.path("optionalDependencies"));
    } catch (IOException failure) {
      throw new IllegalStateException("BUILD_ENVIRONMENT_PACKAGE_JSON_INVALID", failure);
    }
  }

  private static boolean hasFields(JsonNode node) {
    return node.isObject() && node.fieldNames().hasNext();
  }

  private static Path firstExisting(Path root, String... names) {
    for (String name : names) {
      Path candidate = root.resolve(name);
      if (Files.isRegularFile(candidate)) return candidate;
    }
    return null;
  }

  private static List<Path> existing(Path root, Path required, String... optional) {
    List<Path> result = new ArrayList<>();
    result.add(required);
    for (String name : optional) {
      Path candidate = root.resolve(name);
      if (Files.isRegularFile(candidate)) result.add(candidate);
    }
    return List.copyOf(result);
  }

  private static void requireExactPythonPins(Path requirements) {
    try {
      for (String raw : Files.readAllLines(requirements)) {
        String line = raw.strip();
        if (line.isBlank() || line.startsWith("#")) continue;
        if (!line.matches("[A-Za-z0-9_.-]+(\\[[A-Za-z0-9_,.-]+\\])?==[^;\\s]+(?:\\s*;.+)?")) {
          throw new IllegalStateException("BUILD_ENVIRONMENT_PYTHON_LOCK_REQUIRED");
        }
      }
    } catch (IOException failure) {
      throw new IllegalStateException("BUILD_ENVIRONMENT_MANIFEST_READ_FAILED", failure);
    }
  }

  private static String pythonEnvironmentValidator() {
    return """
           import importlib.metadata as m, pathlib, re, sys
           missing=[]
           for raw in pathlib.Path(sys.argv[1]).read_text().splitlines():
             line=raw.strip()
             if not line or line.startswith('#'): continue
             requirement=line.split(';',1)[0].strip()
             name,expected=requirement.split('==',1)
             name=re.sub(r'\\[.*\\]$','',name)
             try: actual=m.version(name)
             except m.PackageNotFoundError: actual=None
             if actual != expected: missing.append(f'{name}=={expected} (installed={actual})')
           if missing:
             print('offline runtime image does not satisfy: '+', '.join(missing), file=sys.stderr)
             raise SystemExit(42)
           """;
  }

  private static String digestFiles(Path root, List<String> paths) {
    StringBuilder canonical = new StringBuilder();
    for (String relative : paths) {
      try {
        byte[] bytes = Files.readAllBytes(root.resolve(relative));
        canonical.append(relative).append('=').append(DigestUtils.sha256Hex(bytes)).append('\n');
      } catch (IOException failure) {
        throw new IllegalStateException("BUILD_ENVIRONMENT_MANIFEST_READ_FAILED", failure);
      }
    }
    return DigestUtils.sha256Hex(canonical.toString());
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception failure) {
      throw new IllegalStateException("BUILD_ENVIRONMENT_PLAN_SERIALIZATION_FAILED", failure);
    }
  }

  private static Path requireRoot(Path root) {
    if (root == null || !root.isAbsolute() || !Files.isDirectory(root)) {
      throw new IllegalArgumentException("BUILD_ENVIRONMENT_WORKSPACE_INVALID");
    }
    return root.toAbsolutePath().normalize();
  }

  private static String relative(Path root, Path path) {
    Path relative = root.relativize(path.toAbsolutePath().normalize());
    if (relative.isAbsolute() || relative.startsWith("..")) {
      throw new IllegalStateException("BUILD_ENVIRONMENT_PATH_ESCAPE");
    }
    String value = relative.toString().replace('\\', '/');
    return value.isBlank() ? "." : value;
  }
}
