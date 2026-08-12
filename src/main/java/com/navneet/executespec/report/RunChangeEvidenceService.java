package com.navneet.executespec.report;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.state.entity.RunRepoSnapshot;
import com.navneet.executespec.state.repository.RunRepoSnapshotRepository;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;
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
    return inspect(runId, workspace, workerReports);
  }

  /** Exact-generation sibling used by disposable BUILD validation. */
  public ChangeEvidence inspect(String runId, Path workspace, List<WorkerReport> workerReports) {
    if (workspace != null) workspace = workspace.toAbsolutePath().normalize();
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
          Path repo = repositoryRoot(workspace, snapshots, snapshot, repoName);
          if (!repo.startsWith(workspace) || !Files.isDirectory(repo.resolve(".git"))) {
            return ChangeEvidence.invalid(
                "admitted repository checkout is unavailable: " + repoName);
          }
          String prefix = repo.equals(workspace) ? "" : "repos/" + repoName + "/";
          collectDiff(repo, snapshot.getCommitSha(), prefix, workerPaths, files);
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
        History history = history(workspace);
        String baseline = history.rootCommit();
        if (baseline == null || baseline.isBlank())
          return ChangeEvidence.invalid("workspace Git baseline is unavailable");
        boolean managedFinalCommit =
            history.commitCount() == 2
                && "init agent workspace".equals(history.rootMessage())
                && ("V2 run output for " + runId).equals(history.headMessage());
        if (history.commitCount() != 1 && !managedFinalCommit) {
          return ChangeEvidence.invalid(
              "no admitted run-start snapshot for this workspace; the repository root commit is"
                  + " not a valid run baseline");
        }
        collectDiff(workspace, baseline, "", workerPaths, files);
        baselines.add("workspace@" + baseline);
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
      throws Exception {
    Set<String> untracked;
    try (Repository repository = open(repo);
        Git git = new Git(repository);
        RevWalk walk = new RevWalk(repository);
        var reader = repository.newObjectReader()) {
      ObjectId baselineId = repository.resolve(baseline);
      if (baselineId == null) throw new IllegalStateException("baseline commit is unavailable");
      RevCommit baselineCommit = walk.parseCommit(baselineId);
      CanonicalTreeParser oldTree = new CanonicalTreeParser();
      oldTree.reset(reader, baselineCommit.getTree());
      FileTreeIterator workingTree = new FileTreeIterator(repository);
      List<DiffEntry> changes =
          git.diff()
              .setOldTree(oldTree)
              .setNewTree(workingTree)
              .setShowNameAndStatusOnly(true)
              .call();
      for (DiffEntry change : changes) {
        String relative =
            (change.getChangeType() == DiffEntry.ChangeType.DELETE
                    ? change.getOldPath()
                    : change.getNewPath())
                .replace('\\', '/');
        addChanged(
            relative, change.getChangeType().name().substring(0, 1), prefix, workerPaths, output);
      }
      untracked = Set.copyOf(git.status().call().getUntracked());
    }
    Set<String> alreadySeen =
        output.stream()
            .map(ChangedFile::path)
            .map(RunChangeEvidenceService::normalize)
            .collect(java.util.stream.Collectors.toSet());
    for (String relativeRaw : untracked.stream().sorted().toList()) {
      if (relativeRaw.isBlank()) continue;
      String relative = relativeRaw.replace('\\', '/');
      String path = prefix + relative;
      if (alreadySeen.contains(normalize(path))) continue;
      addChanged(relative, "A", prefix, workerPaths, output);
    }
  }

  private static void addChanged(
      String relative,
      String status,
      String prefix,
      Set<String> workerPaths,
      List<ChangedFile> output) {
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

  private static Path repositoryRoot(
      Path workspace, List<RunRepoSnapshot> snapshots, RunRepoSnapshot snapshot, String repoName) {
    Path nested = workspace.resolve("repos").resolve(repoName).normalize();
    // Restored archives intentionally omit nested .git metadata and create a synthetic repository
    // at the wrapper root. An existing nested directory still identifies project layout; selecting
    // the wrapper would attribute unrelated repositories to this snapshot. The caller will fail
    // closed when the nested baseline object is unavailable.
    if (snapshot.getRepoId() != null) return nested;
    if (snapshots.size() == 1 && Files.isDirectory(workspace.resolve(".git"))) return workspace;
    return nested;
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

  private static History history(Path workTree) throws Exception {
    try (Repository repository = open(workTree);
        RevWalk walk = new RevWalk(repository)) {
      ObjectId headId = repository.resolve(Constants.HEAD);
      if (headId == null) throw new IllegalStateException("workspace HEAD is unavailable");
      RevCommit head = walk.parseCommit(headId);
      walk.markStart(head);
      int count = 0;
      List<RevCommit> roots = new ArrayList<>();
      for (RevCommit commit : walk) {
        count++;
        if (commit.getParentCount() == 0) roots.add(commit);
      }
      if (roots.size() != 1) {
        throw new IllegalStateException("workspace history must have exactly one root commit");
      }
      RevCommit root = roots.getFirst();
      return new History(
          root.getId().name(), count, root.getFullMessage().strip(), head.getFullMessage().strip());
    }
  }

  private static Repository open(Path workTree) throws Exception {
    return new FileRepositoryBuilder()
        .setWorkTree(workTree.toFile())
        .findGitDir(workTree.toFile())
        .setMustExist(true)
        .build();
  }

  private record History(
      String rootCommit, int commitCount, String rootMessage, String headMessage) {}

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
