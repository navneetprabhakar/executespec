package com.navneet.executespec.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.state.entity.RunRepoSnapshot;
import com.navneet.executespec.state.repository.RunRepoSnapshotRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Computes the product change set from the admitted Git baseline to the final workspace.
 *
 * <p>This is the canonical replacement for treating the final workspace inventory as "files
 * generated". Pre-existing repository files do not appear in a Git diff, and deterministic
 * ExecuteSpec metadata is explicitly excluded. The result is also a reusable goal-evidence seam for
 * the executor: callers can distinguish meaningful product changes from a large but untouched
 * checkout.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunChangeEvidenceService {

  private static final Duration GIT_TIMEOUT = Duration.ofSeconds(60);
  private static final Pattern TOOL_PATH_PATTERN =
      Pattern.compile("\\\"(?:path|file)\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
  private static final Pattern PATCH_PATH_PATTERN =
      Pattern.compile("\\*\\*\\*\\s+(?:Add|Update) File:\\s*([^\\r\\n*]+)");
  private static final Pattern TARGET_PATH_PATTERN = Pattern.compile("\\[target=([^\\]\\r\\n]+)]");
  private static final Set<String> SYSTEM_PATHS =
      Set.of(
          ".gitignore",
          ".gitattributes",
          ".agent-context",
          ".executespec/run.json",
          "api-docs/openapi.yaml");

  private final AgentProperties properties;
  private final RunRepoSnapshotRepository snapshotRepository;

  public ChangeEvidence inspect(String runId, List<WorkerReport> workerReports) {
    Path workspace = Path.of(properties.getWorkspace(), runId).toAbsolutePath().normalize();
    if (!Files.isDirectory(workspace)) {
      return ChangeEvidence.invalid("workspace is unavailable");
    }

    Set<String> workerPaths = claimedWorkerPaths(workerReports);
    List<ChangedFile> files = new ArrayList<>();
    List<String> baselines = new ArrayList<>();
    List<RunRepoSnapshot> snapshots = snapshotRepository.findByRunId(runId);
    try {
      if (!snapshots.isEmpty()) {
        for (RunRepoSnapshot snapshot : snapshots) {
          String repoName = repositoryName(snapshot.getRepoFullName());
          Path repo = workspace.resolve("repos").resolve(repoName).normalize();
          if (!repo.startsWith(workspace) || !Files.isDirectory(repo.resolve(".git"))) {
            return ChangeEvidence.invalid(
                "admitted repository checkout is unavailable: " + repoName);
          }
          collectDiff(repo, snapshot.getCommitSha(), "repos/" + repoName + "/", workerPaths, files);
          baselines.add(snapshot.getRepoFullName() + "@" + snapshot.getCommitSha());
        }
      } else if (Files.isDirectory(workspace.resolve(".git"))) {
        // RR-287 — without an admitted run-start snapshot the only honest baseline is one this run
        // provably created. Diffing against the repository's FIRST-EVER commit counted every
        // change since the repository was created as this run's delivery, so a no-op or failed run
        // against an existing checkout cleared the `filesGenerated > 0` hard floor and earned a
        // nonzero verified score plus a HIGH-confidence "Code delivered" ledger row.
        //
        // A single-commit history is the one case where root IS the run baseline: the workspace
        // was initialised for this run and `gitCommit` is filtered out of worker authority, so
        // nothing can advance HEAD mid-run. Any deeper history is unprovable — fail closed.
        String baseline = git(workspace, "rev-list", "--max-parents=0", "HEAD").trim();
        if (baseline.isBlank())
          return ChangeEvidence.invalid("workspace Git baseline is unavailable");
        String commitCount = git(workspace, "rev-list", "--count", "HEAD").trim();
        boolean managedFinalCommit =
            "2".equals(commitCount)
                && "init agent workspace"
                    .equals(git(workspace, "log", "-1", "--format=%s", baseline).trim())
                && ("V2 run output for " + runId)
                    .equals(git(workspace, "log", "-1", "--format=%s", "HEAD").trim());
        if (!"1".equals(commitCount) && !managedFinalCommit) {
          return ChangeEvidence.invalid(
              "no admitted run-start snapshot for this workspace; the repository root commit is"
                  + " not a valid run baseline");
        }
        collectDiff(
            workspace, baseline.lines().findFirst().orElse(baseline), "", workerPaths, files);
        baselines.add("workspace@" + baseline.lines().findFirst().orElse(baseline));
      } else {
        return ChangeEvidence.invalid("workspace has no admitted Git baseline");
      }
    } catch (Exception e) {
      log.warn("Run {} change evidence failed closed: {}", runId, e.getMessage());
      return ChangeEvidence.invalid("Git change evidence unavailable: " + e.getMessage());
    }

    List<ChangedFile> product = files.stream().filter(ChangedFile::product).toList();
    return new ChangeEvidence(true, null, fingerprint(baselines), List.copyOf(files), product);
  }

  /** Returns the canonical meaningful product-diff count for goal-doneness consumers. */
  public int meaningfulProductDiffCount(String runId) {
    ChangeEvidence evidence = inspect(runId, List.of());
    return evidence.valid() ? evidence.productFiles().size() : 0;
  }

  /**
   * Returns actual product files in the admitted Git diff that were claimed by this worker's
   * successful mutating tool calls. This is intentionally narrower than {@link
   * #meaningfulProductDiffCount(String)}: a later task cannot satisfy its goal by inheriting a file
   * changed by an earlier task, and a README/ExecuteSpec metadata write cannot masquerade as
   * product output.
   */
  public int meaningfulProductDiffCountForToolLog(String runId, List<String> toolLog) {
    Set<String> claimedPaths = claimedPaths(toolLog);
    if (claimedPaths.isEmpty()) return 0;
    ChangeEvidence evidence = inspect(runId, List.of());
    if (!evidence.valid()) return 0;
    return (int)
        evidence.productFiles().stream()
            .map(ChangedFile::path)
            .map(RunChangeEvidenceService::normalize)
            .filter(path -> claimedPaths.stream().anyMatch(claim -> pathMatches(path, claim)))
            .count();
  }

  private static Set<String> claimedPaths(List<String> toolLog) {
    Set<String> paths = new HashSet<>();
    if (toolLog == null) return paths;
    for (String entry : toolLog) {
      if (entry == null || !isSuccessfulMutation(entry)) continue;
      Matcher toolPath = TOOL_PATH_PATTERN.matcher(entry);
      while (toolPath.find()) paths.add(normalize(toolPath.group(1)));
      Matcher patchPath = PATCH_PATH_PATTERN.matcher(entry);
      while (patchPath.find()) paths.add(normalize(patchPath.group(1).trim()));
      Matcher targetPath = TARGET_PATH_PATTERN.matcher(entry);
      while (targetPath.find()) paths.add(normalize(targetPath.group(1).trim()));
    }
    paths.removeIf(String::isBlank);
    return paths;
  }

  private static boolean isSuccessfulMutation(String entry) {
    String lower = entry.toLowerCase(Locale.ROOT);
    boolean mutating =
        lower.startsWith("writefile(")
            || lower.startsWith("write_file(")
            || lower.startsWith("editfile(")
            || lower.startsWith("edit_file(")
            || lower.startsWith("multiedit(")
            || lower.startsWith("multi_edit(")
            || lower.startsWith("applypatch(")
            || lower.startsWith("apply_patch(");
    return mutating && lower.endsWith("-> ok");
  }

  private static boolean pathMatches(String productPath, String claimedPath) {
    return productPath.equals(claimedPath) || productPath.endsWith("/" + claimedPath);
  }

  private void collectDiff(
      Path repo, String baseline, String prefix, Set<String> workerPaths, List<ChangedFile> output)
      throws IOException, InterruptedException {
    String diff = git(repo, "diff", "--name-status", "--find-renames", baseline, "--");
    for (String line : diff.lines().toList()) {
      if (line.isBlank()) continue;
      String[] fields = line.split("\\t");
      if (fields.length < 2) continue;
      String status = fields[0];
      String relative = fields[fields.length - 1].replace('\\', '/');
      String path = prefix + relative;
      boolean workerClaimed =
          workerPaths.contains(normalize(path)) || workerPaths.contains(normalize(relative));
      boolean system =
          isSystemPath(relative) || ("readme.md".equals(normalize(relative)) && !workerClaimed);
      Provenance provenance =
          system
              ? Provenance.SYSTEM
              : workerClaimed ? Provenance.WORKER : Provenance.REVIEWER_OR_POST_PROCESSING;
      output.add(new ChangedFile(path, status, provenance, !system));
    }
    // `git diff <baseline>` does not include untracked worker-created files. Include them from the
    // same checkout, while retaining the deterministic/system exclusions above.
    String untracked = git(repo, "ls-files", "--others", "--exclude-standard");
    Set<String> alreadySeen =
        output.stream()
            .map(ChangedFile::path)
            .map(RunChangeEvidenceService::normalize)
            .collect(java.util.stream.Collectors.toSet());
    for (String relativeRaw : untracked.lines().toList()) {
      if (relativeRaw.isBlank()) continue;
      String relative = relativeRaw.replace('\\', '/');
      String path = prefix + relative;
      if (alreadySeen.contains(normalize(path))) continue;
      boolean workerClaimed =
          workerPaths.contains(normalize(path)) || workerPaths.contains(normalize(relative));
      boolean system =
          isSystemPath(relative) || ("readme.md".equals(normalize(relative)) && !workerClaimed);
      output.add(
          new ChangedFile(
              path,
              "A",
              system
                  ? Provenance.SYSTEM
                  : workerClaimed ? Provenance.WORKER : Provenance.REVIEWER_OR_POST_PROCESSING,
              !system));
    }
  }

  private static boolean isSystemPath(String relative) {
    String path = normalize(relative);
    if (SYSTEM_PATHS.contains(path)) return true;
    return path.startsWith(".executespec/") || path.startsWith(".agent-context/");
  }

  private static Set<String> claimedWorkerPaths(List<WorkerReport> reports) {
    Set<String> paths = new HashSet<>();
    if (reports == null) return paths;
    for (WorkerReport report : reports) {
      if (report == null) continue;
      if (report.getFilesCreated() != null)
        report.getFilesCreated().forEach(p -> paths.add(normalize(p)));
      if (report.getFilesModified() != null)
        report.getFilesModified().forEach(p -> paths.add(normalize(p)));
    }
    return paths;
  }

  private static String normalize(String path) {
    if (path == null) return "";
    String normalized = path.replace('\\', '/');
    while (normalized.startsWith("./")) normalized = normalized.substring(2);
    return normalized.toLowerCase(Locale.ROOT);
  }

  private static String repositoryName(String fullName) {
    if (fullName == null || fullName.isBlank()) return "";
    int slash = fullName.lastIndexOf('/');
    String name = slash >= 0 ? fullName.substring(slash + 1) : fullName;
    return name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
  }

  private static String fingerprint(List<String> values) {
    try {
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      String canonical =
          values.stream().sorted().collect(java.util.stream.Collectors.joining("\n"));
      return java.util.HexFormat.of()
          .formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String git(Path directory, String... arguments)
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(List.of(arguments));
    Path outputFile = Files.createTempFile("executespec-git-evidence-", ".log");
    try {
      Process process =
          new ProcessBuilder(command)
              .directory(directory.toFile())
              .redirectErrorStream(true)
              .redirectOutput(outputFile.toFile())
              .start();
      boolean completed = process.waitFor(GIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      if (!completed) {
        process.destroyForcibly();
        throw new IOException("git command timed out");
      }
      String output = Files.readString(outputFile, StandardCharsets.UTF_8);
      if (process.exitValue() != 0) {
        throw new IOException("git " + arguments[0] + " failed: " + output.trim());
      }
      return output;
    } finally {
      Files.deleteIfExists(outputFile);
    }
  }

  public enum Provenance {
    WORKER,
    REVIEWER_OR_POST_PROCESSING,
    SYSTEM
  }

  public record ChangedFile(String path, String status, Provenance provenance, boolean product) {}

  public record ChangeEvidence(
      boolean valid,
      String invalidReason,
      String baselineFingerprint,
      List<ChangedFile> allFiles,
      List<ChangedFile> productFiles) {
    static ChangeEvidence invalid(String reason) {
      return new ChangeEvidence(false, reason, null, List.of(), List.of());
    }
  }
}
