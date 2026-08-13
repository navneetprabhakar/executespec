package com.navneet.executespec.workspacegeneration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.sandbox.SandboxExecResult;
import com.navneet.executespec.sandboxroute.SandboxCommandRoutingService;
import com.navneet.executespec.sandboxroute.VerificationSandboxAuthority;
import com.navneet.executespec.tool.ExecutionContext;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Executes the server-owned Python HTTP acceptance contract in the strict run sandbox.
 *
 * <p>This is deliberately not a {@code WorkspaceTestRunner} specialization. A generic green test
 * process proves only TEST. Runtime proof additionally requires a digest-pinned container, an
 * attested offline dependency kit, the immutable hidden acceptance script, bounded output, and a
 * completed runtime process. No unavailable branch falls back to the application host.
 */
@Component
@RequiredArgsConstructor
public class PythonHttpRuntimeAcceptanceService {

  static final String RUNTIME_KIT_ID = "python-http-v1";
  static final String RUNTIME_COMMAND = "/opt/executespec/runtime/python-http-acceptance.sh";
  private static final String RUNTIME_ATTESTATION = "EXECUTESPEC_RUNTIME_KIT=" + RUNTIME_KIT_ID;
  private static final int MAX_REQUIREMENTS_BYTES = 32 * 1024;
  private static final Duration TIMEOUT = Duration.ofMinutes(3);
  private static final Pattern REQUIREMENT = Pattern.compile("^([A-Za-z0-9][A-Za-z0-9._-]*)(.*)$");
  private static final Pattern PYTEST_COUNTS =
      Pattern.compile("(?:(\\d+) passed)?(?:,?\\s*(\\d+) failed)?(?:,?\\s*(\\d+) errors?)?");
  private static final Set<String> ALLOWED_DECLARATIONS =
      Set.of("fastapi", "uvicorn", "pydantic", "typing-extensions");

  /** Versions installed from the immutable offline wheelhouse. */
  private static final Map<String, String> SUPPORTED_VERSIONS =
      Map.of("fastapi", "0.115.12", "uvicorn", "0.34.3", "pydantic", "2.11.5");

  private final SandboxCommandRoutingService sandboxRouting;

  public Result verify(String runId, Path workspaceRoot) {
    return verify(runId, workspaceRoot, null);
  }

  public Result verify(ExecutionContext context) {
    return verify(context.runId(), context.workspaceRoot(), context.verificationSandboxAuthority());
  }

  private Result verify(
      String runId, Path workspaceRoot, VerificationSandboxAuthority verificationAuthority) {
    DependencyDecision dependencies = validateDependencies(workspaceRoot);
    if (!dependencies.valid()) {
      return Result.failedBeforeExecution(dependencies.reason(), dependencies.evidence());
    }

    SandboxCommandRoutingService.Readiness readiness = sandboxRouting.readiness();
    Map<String, Object> evidence = new LinkedHashMap<>(dependencies.evidence());
    evidence.put("runtimeKitId", RUNTIME_KIT_ID);
    evidence.put("runtimeCommand", RUNTIME_COMMAND);
    evidence.put("sandboxImage", sandboxRouting.runtimeImageIdentity());
    evidence.put("sandboxReady", readiness.enabled());
    evidence.put("sandboxReadinessReason", readiness.reason());
    if (!readiness.enabled()) {
      return Result.unavailable("strict runtime sandbox is unavailable", evidence);
    }

    if (!cleanupRuntimeResidue(workspaceRoot)) {
      return Result.unavailable("stale runtime environment could not be removed", evidence);
    }

    SandboxExecResult executed;
    boolean cleanupComplete;
    try {
      executed =
          verificationAuthority == null
              ? sandboxRouting.execute(runId, workspaceRoot, List.of(RUNTIME_COMMAND), TIMEOUT)
              : sandboxRouting.executeVerificationShell(
                  verificationAuthority, RUNTIME_COMMAND, TIMEOUT);
    } finally {
      cleanupComplete = cleanupRuntimeResidue(workspaceRoot);
    }
    String output = SandboxCommandRoutingService.combinedOutput(executed);
    evidence.put("sandboxStatus", executed.status().name());
    evidence.put("exitCode", executed.completed() ? executed.exitCode() : null);
    evidence.put("durationMillis", executed.duration().toMillis());
    evidence.put("outputTruncated", executed.outputTruncated());
    evidence.put("outputSha256", DigestUtils.sha256Hex(output));
    evidence.put("runtimeKitAttested", output.contains(RUNTIME_ATTESTATION));
    evidence.put("runtimeEnvironmentCleanupComplete", cleanupComplete);
    evidence.put("boundedOutput", bounded(output));

    if (!cleanupComplete) {
      return Result.unavailable("runtime environment cleanup could not be verified", evidence);
    }

    if (!executed.completed()) {
      return Result.unavailable(
          "runtime command did not complete: " + executed.status() + ": " + executed.detail(),
          evidence);
    }
    if (executed.outputTruncated()) {
      return Result.unavailable("runtime evidence was truncated", evidence);
    }
    if (!output.contains(RUNTIME_ATTESTATION)) {
      return Result.unavailable("runtime kit attestation is absent", evidence);
    }

    Counts counts = counts(output, executed.exitCode());
    if (executed.exitCode() != 0) {
      return new Result(
          Status.FAILED,
          true,
          false,
          counts.passed(),
          counts.failed(),
          counts.errors(),
          counts.total(),
          "runtime contract failed",
          Map.copyOf(evidence));
    }
    if (counts.total() <= 0 || counts.failed() > 0 || counts.errors() > 0) {
      return Result.unavailable("runtime test counts are not trustworthy", evidence);
    }
    return new Result(
        Status.PASSED,
        true,
        true,
        counts.passed(),
        0,
        0,
        counts.total(),
        "runtime contract passed",
        Map.copyOf(evidence));
  }

  DependencyDecision validateDependencies(Path workspaceRoot) {
    if (workspaceRoot == null) {
      return DependencyDecision.invalid("workspace root is absent", Map.of());
    }
    Path root = workspaceRoot.toAbsolutePath().normalize();
    Path requirements = root.resolve("requirements.txt").normalize();
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("dependencyManifest", "requirements.txt");
    try {
      if (!requirements.startsWith(root)
          || !Files.isRegularFile(requirements, LinkOption.NOFOLLOW_LINKS)
          || Files.isSymbolicLink(requirements)) {
        return DependencyDecision.invalid(
            "requirements.txt must be a regular non-symlink file", evidence);
      }
      long size = Files.size(requirements);
      evidence.put("dependencyManifestBytes", size);
      if (size <= 0 || size > MAX_REQUIREMENTS_BYTES) {
        return DependencyDecision.invalid("requirements.txt is empty or exceeds 32 KiB", evidence);
      }
      Path realRoot = root.toRealPath();
      Path realRequirements = requirements.toRealPath();
      if (!realRequirements.startsWith(realRoot)) {
        return DependencyDecision.invalid(
            "requirements.txt resolves outside the workspace", evidence);
      }
      byte[] bytes = Files.readAllBytes(requirements);
      evidence.put("dependencyManifestSha256", DigestUtils.sha256Hex(bytes));
      String source = new String(bytes, StandardCharsets.UTF_8);
      Map<String, String> declared = parseRequirements(source);
      evidence.put("declaredDependencies", declared);
      if (!declared.keySet().containsAll(Set.of("fastapi", "uvicorn"))) {
        return DependencyDecision.invalid(
            "requirements.txt must declare fastapi and uvicorn", evidence);
      }
      for (Map.Entry<String, String> declaration : declared.entrySet()) {
        String supported = SUPPORTED_VERSIONS.get(declaration.getKey());
        if (supported != null && !constraintAllows(supported, declaration.getValue())) {
          return DependencyDecision.invalid(
              "offline runtime version "
                  + supported
                  + " does not satisfy "
                  + declaration.getKey()
                  + declaration.getValue(),
              evidence);
        }
      }
      evidence.put("offlineRuntimeVersions", SUPPORTED_VERSIONS);
      return new DependencyDecision(true, "dependency declaration accepted", Map.copyOf(evidence));
    } catch (IllegalArgumentException invalid) {
      return DependencyDecision.invalid(invalid.getMessage(), evidence);
    } catch (IOException failure) {
      return DependencyDecision.invalid(
          "requirements.txt could not be read: " + failure.getClass().getSimpleName(), evidence);
    }
  }

  private static Map<String, String> parseRequirements(String source) {
    Map<String, String> declared = new LinkedHashMap<>();
    for (String raw : source.split("\\R", -1)) {
      String line = raw.strip();
      if (line.isEmpty() || line.startsWith("#")) continue;
      int comment = line.indexOf(" #");
      if (comment >= 0) line = line.substring(0, comment).strip();
      if (line.startsWith("-")
          || line.contains("://")
          || line.contains("@")
          || line.contains(";")
          || line.contains("\\")) {
        throw new IllegalArgumentException("unsafe dependency declaration: " + boundedLine(line));
      }
      Matcher matcher = REQUIREMENT.matcher(line);
      if (!matcher.matches()) {
        throw new IllegalArgumentException(
            "malformed dependency declaration: " + boundedLine(line));
      }
      String name = matcher.group(1).toLowerCase(Locale.ROOT).replace('_', '-');
      String constraint = matcher.group(2).replace(" ", "");
      if (!ALLOWED_DECLARATIONS.contains(name)) {
        throw new IllegalArgumentException("dependency is not in the runtime allowlist: " + name);
      }
      if (!constraint.matches("(?:[<>=!~]=?[0-9A-Za-z.*+_-]+(?:,[<>=!~]=?[0-9A-Za-z.*+_-]+)*)?")) {
        throw new IllegalArgumentException(
            "unsupported dependency constraint: " + boundedLine(line));
      }
      String previous = declared.putIfAbsent(name, constraint);
      if (previous != null && !previous.equals(constraint)) {
        throw new IllegalArgumentException("conflicting duplicate dependency declaration: " + name);
      }
    }
    return Map.copyOf(declared);
  }

  private static boolean constraintAllows(String supported, String constraint) {
    if (constraint == null || constraint.isBlank()) return true;
    for (String part : constraint.split(",")) {
      String operator = part.replaceFirst("^([<>=!~]+).*$", "$1");
      String requested = part.substring(operator.length());
      int comparison = compareVersions(supported, requested.replace(".*", ".0"));
      boolean allowed =
          switch (operator) {
            case "==" ->
                requested.endsWith(".*")
                    ? supported.startsWith(requested.substring(0, requested.length() - 1))
                    : comparison == 0;
            case "!=" -> comparison != 0;
            case ">=" -> comparison >= 0;
            case "<=" -> comparison <= 0;
            case ">" -> comparison > 0;
            case "<" -> comparison < 0;
            case "~=" -> compatibleRelease(supported, requested) && comparison >= 0;
            default -> false;
          };
      if (!allowed) return false;
    }
    return true;
  }

  private static boolean compatibleRelease(String supported, String requested) {
    List<Integer> requestedParts = numericParts(requested);
    List<Integer> supportedParts = numericParts(supported);
    int prefix = Math.max(1, requestedParts.size() - 1);
    for (int i = 0; i < prefix; i++) {
      if (!supportedParts.get(i).equals(requestedParts.get(i))) return false;
    }
    return true;
  }

  private static int compareVersions(String left, String right) {
    List<Integer> a = numericParts(left);
    List<Integer> b = numericParts(right);
    for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
      int av = i < a.size() ? a.get(i) : 0;
      int bv = i < b.size() ? b.get(i) : 0;
      if (av != bv) return Integer.compare(av, bv);
    }
    return 0;
  }

  private static List<Integer> numericParts(String value) {
    List<Integer> parts = new ArrayList<>();
    for (String part : value.split("\\.")) {
      Matcher digits = Pattern.compile("^(\\d+)").matcher(part);
      if (!digits.find()) throw new IllegalArgumentException("unsupported version: " + value);
      parts.add(Integer.parseInt(digits.group(1)));
    }
    while (parts.size() < 3) parts.add(0);
    return parts;
  }

  private static Counts counts(String output, int exitCode) {
    int passed = 0;
    int failed = 0;
    int errors = 0;
    Matcher matcher = PYTEST_COUNTS.matcher(output == null ? "" : output);
    while (matcher.find()) {
      if (matcher.group().isBlank()) continue;
      if (matcher.group(1) != null) passed = Integer.parseInt(matcher.group(1));
      if (matcher.group(2) != null) failed = Integer.parseInt(matcher.group(2));
      if (matcher.group(3) != null) errors = Integer.parseInt(matcher.group(3));
    }
    if (exitCode != 0 && failed + errors == 0) failed = 1;
    return new Counts(passed, failed, errors, passed + failed + errors);
  }

  private static String bounded(String value) {
    if (value == null || value.length() <= 12_000) return value;
    return value.substring(0, 8_000)
        + "\n...[truncated by evidence serializer]...\n"
        + value.substring(value.length() - 4_000);
  }

  private static String boundedLine(String line) {
    return line.length() <= 160 ? line : line.substring(0, 160) + "...";
  }

  private static boolean cleanupRuntimeResidue(Path workspaceRoot) {
    if (workspaceRoot == null) return false;
    Path root = workspaceRoot.toAbsolutePath().normalize();
    Path executespec = root.resolve(".executespec").normalize();
    try {
      if (!executespec.startsWith(root)
          || !Files.isDirectory(executespec, LinkOption.NOFOLLOW_LINKS)
          || Files.isSymbolicLink(executespec)) return false;
      try (var entries = Files.newDirectoryStream(executespec, "runtime-python-http.*")) {
        for (Path residue : entries) {
          Path normalized = residue.toAbsolutePath().normalize();
          if (!normalized.getParent().equals(executespec)
              || !normalized.getFileName().toString().startsWith("runtime-python-http.")) {
            return false;
          }
          Files.walkFileTree(
              normalized,
              Set.of(),
              Integer.MAX_VALUE,
              new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                  Files.deleteIfExists(file);
                  return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException failure)
                    throws IOException {
                  if (failure != null) throw failure;
                  Files.deleteIfExists(dir);
                  return FileVisitResult.CONTINUE;
                }
              });
        }
      }
      return true;
    } catch (IOException | RuntimeException failure) {
      return false;
    }
  }

  public enum Status {
    PASSED,
    FAILED,
    UNAVAILABLE
  }

  public record Result(
      Status status,
      boolean executed,
      boolean passed,
      int testsPassed,
      int testsFailed,
      int testsErrors,
      int testsTotal,
      String detail,
      Map<String, Object> evidence) {

    static Result failedBeforeExecution(String detail, Map<String, Object> evidence) {
      return new Result(Status.FAILED, false, false, 0, 1, 0, 1, detail, Map.copyOf(evidence));
    }

    static Result unavailable(String detail, Map<String, Object> evidence) {
      return new Result(Status.UNAVAILABLE, false, false, 0, 0, 0, 0, detail, Map.copyOf(evidence));
    }
  }

  record DependencyDecision(boolean valid, String reason, Map<String, Object> evidence) {
    static DependencyDecision invalid(String reason, Map<String, Object> evidence) {
      return new DependencyDecision(false, reason, Map.copyOf(evidence));
    }
  }

  private record Counts(int passed, int failed, int errors, int total) {}
}
