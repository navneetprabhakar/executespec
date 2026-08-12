package com.navneet.executespec.integration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.springframework.stereotype.Component;

/**
 * Builds a restorable Git tree identity without invoking Git or repository-configured helpers.
 *
 * <p>The former temporary-index {@code git add -A} path could execute clean filters from the
 * repository config on the application host. This implementation uses JGit only to enumerate the
 * index/ignore state and insert raw blobs/trees. It never applies clean/smudge filters, hooks,
 * fsmonitor, textconv, external diff or credential helpers.
 */
@Component
public final class NonExecutingGitTreeWriter {

  public String head(Path workTree) {
    try (Repository repository = open(workTree)) {
      ObjectId head = repository.resolve(Constants.HEAD);
      if (head == null) throw new IllegalStateException("repository HEAD is unavailable");
      return head.name();
    } catch (IOException failure) {
      throw new IllegalStateException("could not resolve repository HEAD", failure);
    }
  }

  public String writeTree(
      Path workTree, List<String> excludedRepositories, Runnable authorityFence) {
    authorityFence.run();
    Path root = workTree.toAbsolutePath().normalize();
    try (Repository repository = open(root);
        Git git = new Git(repository);
        ObjectInserter inserter = repository.newObjectInserter()) {
      var status = git.status().call();
      Set<String> removed = new HashSet<>();
      removed.addAll(status.getMissing());
      removed.addAll(status.getRemoved());

      TreeSet<String> paths = new TreeSet<>();
      DirCache liveIndex = repository.readDirCache();
      for (int i = 0; i < liveIndex.getEntryCount(); i++) {
        DirCacheEntry entry = liveIndex.getEntry(i);
        if (entry.getStage() != 0) continue;
        String path = normalized(entry.getPathString());
        if (!removed.contains(path) && !excluded(path, excludedRepositories)) paths.add(path);
      }
      for (String untracked : status.getUntracked()) {
        String path = normalized(untracked);
        if (!excluded(path, excludedRepositories)) paths.add(path);
      }

      DirCache generated = DirCache.newInCore();
      DirCacheBuilder builder = generated.builder();
      for (String relative : paths) {
        authorityFence.run();
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root)) {
          throw new IllegalStateException("Git path escaped workspace: " + relative);
        }
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue;
        Blob blob = blob(file, inserter);
        DirCacheEntry entry = new DirCacheEntry(relative);
        entry.setFileMode(blob.mode());
        entry.setObjectId(blob.id());
        builder.add(entry);
      }
      builder.finish();
      authorityFence.run();
      ObjectId tree = generated.writeTree(inserter);
      inserter.flush();
      return tree.name();
    } catch (Exception failure) {
      throw new IllegalStateException("non-executing Git tree construction failed", failure);
    }
  }

  /**
   * Inserts the exact worktree tree and, when it differs from HEAD, one commit object without
   * invoking Git, updating the index, moving a ref, or running repository-controlled helpers.
   *
   * <p>The caller supplies and durably records {@code committedAt} before this method runs. A crash
   * retry can therefore reconstruct the same content-addressed commit instead of inventing a new
   * publication artifact. A clean tree resolves to the existing HEAD commit and creates no empty
   * commit.
   */
  public PreparedCommit prepareCommit(
      Path workTree, String message, Instant committedAt, Runnable authorityFence) {
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException("publication commit message is required");
    }
    if (committedAt == null) {
      throw new IllegalArgumentException("publication commit timestamp is required");
    }
    authorityFence.run();
    String treeId = writeTree(workTree, List.of(), authorityFence);
    try (Repository repository = open(workTree);
        RevWalk walk = new RevWalk(repository);
        ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId parentId = repository.resolve(Constants.HEAD);
      if (parentId == null) throw new IllegalStateException("repository HEAD is unavailable");
      var parent = walk.parseCommit(parentId);
      if (parent.getTree().getId().name().equals(treeId)) {
        return new PreparedCommit(parentId.name(), treeId, parentId.name(), false);
      }
      authorityFence.run();
      PersonIdent identity =
          new PersonIdent(
              "ExecuteSpec Agent", "agent@executespec.local", committedAt, ZoneOffset.UTC);
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(ObjectId.fromString(treeId));
      commit.setParentId(parentId);
      commit.setAuthor(identity);
      commit.setCommitter(identity);
      commit.setMessage(message);
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();
      authorityFence.run();
      return new PreparedCommit(parentId.name(), treeId, commitId.name(), true);
    } catch (Exception failure) {
      throw new IllegalStateException("non-executing Git commit construction failed", failure);
    }
  }

  public record PreparedCommit(
      String parentCommitId, String treeId, String commitId, boolean hasChanges) {}

  private static Blob blob(Path file, ObjectInserter inserter) throws IOException {
    if (Files.isSymbolicLink(file)) {
      byte[] target = Files.readSymbolicLink(file).toString().getBytes(StandardCharsets.UTF_8);
      return new Blob(
          FileMode.SYMLINK,
          inserter.insert(Constants.OBJ_BLOB, target.length, new ByteArrayInputStream(target)));
    }
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("unsupported Git worktree entry: " + file);
    }
    FileMode mode = executable(file) ? FileMode.EXECUTABLE_FILE : FileMode.REGULAR_FILE;
    try (var input = Files.newInputStream(file)) {
      return new Blob(mode, inserter.insert(Constants.OBJ_BLOB, Files.size(file), input));
    }
  }

  private static boolean executable(Path file) {
    try {
      return Files.getPosixFilePermissions(file).contains(PosixFilePermission.OWNER_EXECUTE);
    } catch (UnsupportedOperationException | IOException ignored) {
      return Files.isExecutable(file);
    }
  }

  private static boolean excluded(String path, List<String> repositories) {
    if (repositories == null) return false;
    for (String candidate : repositories) {
      String nested = normalized(candidate);
      if (path.equals(nested) || path.startsWith(nested + "/")) return true;
    }
    return false;
  }

  private static String normalized(String path) {
    if (path == null) return "";
    String normalized = path.replace('\\', '/');
    while (normalized.startsWith("./")) normalized = normalized.substring(2);
    if (normalized.isBlank()
        || normalized.startsWith("/")
        || normalized.equals("..")
        || normalized.startsWith("../")
        || normalized.contains("/../")) {
      throw new IllegalArgumentException("invalid Git worktree path");
    }
    return normalized;
  }

  private static Repository open(Path workTree) throws IOException {
    return new FileRepositoryBuilder()
        .setWorkTree(workTree.toFile())
        .findGitDir(workTree.toFile())
        .setMustExist(true)
        .build();
  }

  private record Blob(FileMode mode, ObjectId id) {}
}
