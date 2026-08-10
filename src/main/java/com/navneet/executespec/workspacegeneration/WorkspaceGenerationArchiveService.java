package com.navneet.executespec.workspacegeneration;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.testgen.BoundedProcess;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Exact, internal archive for a content-addressed BUILD generation.
 *
 * <p>This is intentionally separate from the customer download ZIP. It retains one Git bundle for
 * every repository's original HEAD plus the exact candidate tree (including file modes), so a
 * different node can reconstruct the same multi-repository generation and prove it with the normal
 * {@link CorrectionWorkspaceTransaction} calculation. Repository-controlled hooks are never run.
 */
@Service
@RequiredArgsConstructor
public class WorkspaceGenerationArchiveService {

  private static final int FORMAT_VERSION = 1;
  private static final int MAX_REPOSITORIES = 32;
  private static final int MAX_ENTRIES = 200_000;
  private static final long MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024;
  private static final long MAX_ENTRY_BYTES = 2L * 1024 * 1024 * 1024;
  private static final Duration GIT_TIMEOUT = Duration.ofMinutes(2);

  private final ObjectMapper objectMapper;
  private final CorrectionWorkspaceTransaction workspaceTransactions;

  public Path create(CorrectionWorkspaceTransaction.Checkpoint checkpoint) {
    if (checkpoint.repositories().size() > MAX_REPOSITORIES) {
      throw new IllegalStateException("WORKSPACE_GENERATION_ARCHIVE_REPOSITORY_LIMIT");
    }
    Path scratch = null;
    Path archive = null;
    try {
      scratch = Files.createTempDirectory("es-generation-source-");
      archive = Files.createTempFile("es-generation-", ".tar.gz");
      List<RepositoryManifest> repositories = new ArrayList<>();
      for (int i = 0; i < checkpoint.repositories().size(); i++) {
        CorrectionWorkspaceTransaction.RepositoryCheckpoint repository =
            checkpoint.repositories().get(i);
        String relative = relative(checkpoint.workspaceRoot(), repository.repository());
        Path bundle = scratch.resolve(String.format("%03d.bundle", i));
        Path tree = scratch.resolve(String.format("%03d.tree.tar", i));
        runGit(
            repository.repository(),
            "git",
            "-c",
            "core.hooksPath=/dev/null",
            "bundle",
            "create",
            bundle.toString(),
            "HEAD");
        runGit(
            repository.repository(),
            "git",
            "-c",
            "core.hooksPath=/dev/null",
            "archive",
            "--format=tar",
            "--output=" + tree,
            repository.tree());
        repositories.add(new RepositoryManifest(i, relative, repository.head(), repository.tree()));
      }
      Manifest manifest =
          new Manifest(FORMAT_VERSION, checkpoint.generation(), List.copyOf(repositories));
      writeArchive(archive, scratch, manifest);
      return archive;
    } catch (Exception failure) {
      if (archive != null) deleteQuietly(archive);
      throw new IllegalStateException("WORKSPACE_GENERATION_ARCHIVE_CREATE_FAILED", failure);
    } finally {
      deleteTreeQuietly(scratch);
    }
  }

  /** Restores only into an absent workspace and returns a freshly attested checkpoint. */
  public CorrectionWorkspaceTransaction.Checkpoint restore(
      Path archive, String expectedArtifactDigest, Path workspaceRoot, String expectedGeneration) {
    requireHash(expectedArtifactDigest, "WORKSPACE_GENERATION_ARCHIVE_DIGEST_INVALID");
    requireHash(expectedGeneration, "WORKSPACE_GENERATION_ARCHIVE_GENERATION_INVALID");
    Path target = workspaceRoot.toAbsolutePath().normalize();
    if (Files.exists(target)) {
      throw new IllegalStateException("WORKSPACE_GENERATION_ARCHIVE_TARGET_ALREADY_EXISTS");
    }
    String actualDigest;
    try (var input = Files.newInputStream(archive)) {
      actualDigest = org.apache.commons.codec.digest.DigestUtils.sha256Hex(input);
    } catch (IOException failure) {
      throw new IllegalStateException("WORKSPACE_GENERATION_ARCHIVE_DIGEST_FAILED", failure);
    }
    if (!actualDigest.equals(expectedArtifactDigest)) {
      throw new IllegalStateException("WORKSPACE_GENERATION_ARCHIVE_DIGEST_MISMATCH");
    }

    Path staging = null;
    try {
      staging = Files.createTempDirectory("es-generation-restore-");
      extractArchive(archive, staging);
      Manifest manifest =
          objectMapper.readValue(staging.resolve("manifest.json").toFile(), Manifest.class);
      validateManifest(manifest, expectedGeneration);
      Files.createDirectories(target.getParent());
      boolean rootRepository = manifest.repositories().stream().anyMatch(r -> ".".equals(r.path()));
      if (!rootRepository) Files.createDirectories(target);
      for (RepositoryManifest repository : manifest.repositories()) {
        Path destination =
            ".".equals(repository.path()) ? target : safeResolve(target, repository.path());
        if (!".".equals(repository.path())) Files.createDirectories(destination.getParent());
        Path bundle =
            staging.resolve("bundles").resolve(String.format("%03d.bundle", repository.index()));
        runGit(
            target.getParent(),
            "git",
            "-c",
            "core.hooksPath=/dev/null",
            "clone",
            "--no-checkout",
            bundle.toString(),
            destination.toString());
        String head = gitOutput(destination, "git", "rev-parse", "HEAD").trim();
        if (!repository.head().equals(head)) {
          throw new IllegalStateException("WORKSPACE_GENERATION_ARCHIVE_HEAD_MISMATCH");
        }
        copyTree(
            staging.resolve("trees").resolve(String.format("%03d", repository.index())),
            destination);
        runGit(
            destination,
            "git",
            "-c",
            "core.hooksPath=/dev/null",
            "reset",
            "--mixed",
            "--quiet",
            repository.head());
      }
      CorrectionWorkspaceTransaction.Checkpoint restored = workspaceTransactions.begin(target);
      if (!expectedGeneration.equals(restored.generation())) {
        throw new IllegalStateException("WORKSPACE_GENERATION_ARCHIVE_RESTORE_UNPROVEN");
      }
      return restored;
    } catch (Exception failure) {
      deleteTreeQuietly(target);
      throw new IllegalStateException("WORKSPACE_GENERATION_ARCHIVE_RESTORE_FAILED", failure);
    } finally {
      deleteTreeQuietly(staging);
    }
  }

  private void writeArchive(Path archive, Path scratch, Manifest manifest) throws IOException {
    try (TarArchiveOutputStream tar =
        new TarArchiveOutputStream(
            new GzipCompressorOutputStream(
                new BufferedOutputStream(Files.newOutputStream(archive))))) {
      tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
      tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
      byte[] manifestBytes = objectMapper.writeValueAsBytes(manifest);
      addBytes(tar, "manifest.json", manifestBytes, 0644);
      for (RepositoryManifest repository : manifest.repositories()) {
        Path bundle = scratch.resolve(String.format("%03d.bundle", repository.index()));
        addFile(tar, "bundles/" + String.format("%03d.bundle", repository.index()), bundle, 0600);
        Path treeTar = scratch.resolve(String.format("%03d.tree.tar", repository.index()));
        try (TarArchiveInputStream input =
            new TarArchiveInputStream(new BufferedInputStream(Files.newInputStream(treeTar)))) {
          TarArchiveEntry entry;
          while ((entry = input.getNextTarEntry()) != null) {
            if (entry.isDirectory()) continue;
            if (!entry.isFile() && !entry.isSymbolicLink()) {
              throw new IOException("Unsupported Git tree entry: " + entry.getName());
            }
            String safeName = safeArchivePath(entry.getName());
            String name = "trees/" + String.format("%03d", repository.index()) + "/" + safeName;
            TarArchiveEntry copy = new TarArchiveEntry(name, entry.isSymbolicLink());
            copy.setMode(entry.getMode());
            copy.setModTime(0L);
            if (entry.isSymbolicLink()) {
              validateSymlink(safeName, entry.getLinkName());
              copy.setLinkName(entry.getLinkName());
              copy.setSize(0);
            } else {
              copy.setSize(entry.getSize());
            }
            tar.putArchiveEntry(copy);
            if (entry.isFile()) input.transferTo(tar);
            tar.closeArchiveEntry();
          }
        }
      }
      tar.finish();
    }
  }

  private void extractArchive(Path archive, Path staging) throws IOException {
    int entries = 0;
    long total = 0;
    byte[] buffer = new byte[8192];
    try (TarArchiveInputStream tar =
        new TarArchiveInputStream(
            new GzipCompressorInputStream(
                new BufferedInputStream(Files.newInputStream(archive))))) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextTarEntry()) != null) {
        if (++entries > MAX_ENTRIES) throw new IOException("generation archive entry limit");
        String name = safeArchivePath(entry.getName());
        Path destination = safeResolve(staging, name);
        if (entry.isDirectory()) {
          Files.createDirectories(destination);
          continue;
        }
        Files.createDirectories(destination.getParent());
        if (entry.isSymbolicLink()) {
          validateSymlink(name, entry.getLinkName());
          Files.createSymbolicLink(destination, Path.of(entry.getLinkName()));
          continue;
        }
        if (!entry.isFile() || entry.getSize() > MAX_ENTRY_BYTES) {
          throw new IOException("unsupported or over-limit generation archive entry");
        }
        long written = 0;
        try (var output = new BufferedOutputStream(Files.newOutputStream(destination))) {
          int count;
          while ((count = tar.read(buffer)) != -1) {
            written += count;
            total += count;
            if (written > entry.getSize() || written > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
              throw new IOException("generation archive expanded-size limit");
            }
            output.write(buffer, 0, count);
          }
        }
        applyMode(destination, entry.getMode());
      }
    }
  }

  private static void copyTree(Path source, Path destination) throws IOException {
    if (!Files.isDirectory(source)) return;
    Files.walkFileTree(
        source,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Files.createDirectories(destination.resolve(source.relativize(dir)));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Path target = destination.resolve(source.relativize(file));
            if (Files.isSymbolicLink(file)) {
              Path link = Files.readSymbolicLink(file);
              validateSymlink(posix(source.relativize(file)), link.toString());
              Files.createSymbolicLink(target, link);
            } else {
              Files.copy(
                  file,
                  target,
                  StandardCopyOption.COPY_ATTRIBUTES,
                  StandardCopyOption.REPLACE_EXISTING);
            }
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static void addBytes(TarArchiveOutputStream tar, String name, byte[] bytes, int mode)
      throws IOException {
    TarArchiveEntry entry = new TarArchiveEntry(name);
    entry.setSize(bytes.length);
    entry.setMode(mode);
    entry.setModTime(0L);
    tar.putArchiveEntry(entry);
    tar.write(bytes);
    tar.closeArchiveEntry();
  }

  private static void addFile(TarArchiveOutputStream tar, String name, Path file, int mode)
      throws IOException {
    TarArchiveEntry entry = new TarArchiveEntry(name);
    entry.setSize(Files.size(file));
    entry.setMode(mode);
    entry.setModTime(0L);
    tar.putArchiveEntry(entry);
    Files.copy(file, tar);
    tar.closeArchiveEntry();
  }

  private static void validateManifest(Manifest manifest, String expectedGeneration) {
    if (manifest == null
        || manifest.version() != FORMAT_VERSION
        || !expectedGeneration.equals(manifest.generation())
        || manifest.repositories() == null
        || manifest.repositories().isEmpty()
        || manifest.repositories().size() > MAX_REPOSITORIES) {
      throw new IllegalStateException("WORKSPACE_GENERATION_ARCHIVE_MANIFEST_INVALID");
    }
    Set<Integer> indexes = new java.util.HashSet<>();
    for (RepositoryManifest repository : manifest.repositories()) {
      if (!indexes.add(repository.index()))
        throw new IllegalStateException("WORKSPACE_GENERATION_ARCHIVE_DUPLICATE_REPOSITORY");
      safeArchivePath(repository.path());
      requireHash(repository.head(), "WORKSPACE_GENERATION_ARCHIVE_HEAD_INVALID");
      requireHash(repository.tree(), "WORKSPACE_GENERATION_ARCHIVE_TREE_INVALID");
    }
  }

  private static void runGit(Path directory, String... command) {
    ProcessBuilder process =
        new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
    try {
      BoundedProcess.Outcome outcome = BoundedProcess.run(process, GIT_TIMEOUT);
      if (!outcome.finished() || outcome.exitCode() != 0) {
        throw new IllegalStateException(
            "WORKSPACE_GENERATION_ARCHIVE_GIT_FAILED: " + bounded(outcome.output()));
      }
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalStateException("WORKSPACE_GENERATION_ARCHIVE_GIT_FAILED", failure);
    }
  }

  private static String gitOutput(Path directory, String... command) {
    ProcessBuilder process =
        new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
    try {
      BoundedProcess.Outcome outcome = BoundedProcess.run(process, GIT_TIMEOUT);
      if (!outcome.finished() || outcome.exitCode() != 0) {
        throw new IllegalStateException("WORKSPACE_GENERATION_ARCHIVE_GIT_READ_FAILED");
      }
      return outcome.output();
    } catch (RuntimeException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IllegalStateException("WORKSPACE_GENERATION_ARCHIVE_GIT_READ_FAILED", failure);
    }
  }

  private static String relative(Path root, Path repository) {
    Path canonicalRoot = root.toAbsolutePath().normalize();
    Path canonicalRepository = repository.toAbsolutePath().normalize();
    if (!canonicalRepository.startsWith(canonicalRoot)) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_ARCHIVE_REPOSITORY_ESCAPES_ROOT");
    }
    return canonicalRoot.equals(canonicalRepository)
        ? "."
        : canonicalRoot.relativize(canonicalRepository).toString().replace('\\', '/');
  }

  private static String safeArchivePath(String value) {
    if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_ARCHIVE_PATH_INVALID");
    }
    Path path = Path.of(value).normalize();
    if (path.isAbsolute() || path.startsWith("..")) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_ARCHIVE_PATH_ESCAPES_ROOT");
    }
    return path.toString().replace('\\', '/');
  }

  private static Path safeResolve(Path root, String value) {
    Path result = root.resolve(safeArchivePath(value)).normalize();
    if (!result.startsWith(root)) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_ARCHIVE_PATH_ESCAPES_ROOT");
    }
    return result;
  }

  private static void validateSymlink(String entryName, String value) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("WORKSPACE_GENERATION_ARCHIVE_SYMLINK_INVALID");
    Path target = Path.of(value).normalize();
    Path entry = Path.of(safeArchivePath(entryName));
    Path parent = entry.getParent() == null ? Path.of("") : entry.getParent();
    Path resolved = parent.resolve(target).normalize();
    if (target.isAbsolute() || resolved.startsWith("..")) {
      throw new IllegalArgumentException("WORKSPACE_GENERATION_ARCHIVE_SYMLINK_ESCAPES_ROOT");
    }
  }

  private static String posix(Path path) {
    return path.toString().replace('\\', '/');
  }

  private static void applyMode(Path path, int mode) {
    try {
      Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
      if ((mode & 0400) != 0) permissions.add(PosixFilePermission.OWNER_READ);
      if ((mode & 0200) != 0) permissions.add(PosixFilePermission.OWNER_WRITE);
      if ((mode & 0100) != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE);
      if ((mode & 0040) != 0) permissions.add(PosixFilePermission.GROUP_READ);
      if ((mode & 0020) != 0) permissions.add(PosixFilePermission.GROUP_WRITE);
      if ((mode & 0010) != 0) permissions.add(PosixFilePermission.GROUP_EXECUTE);
      if ((mode & 0004) != 0) permissions.add(PosixFilePermission.OTHERS_READ);
      if ((mode & 0002) != 0) permissions.add(PosixFilePermission.OTHERS_WRITE);
      if ((mode & 0001) != 0) permissions.add(PosixFilePermission.OTHERS_EXECUTE);
      Files.setPosixFilePermissions(path, permissions);
    } catch (UnsupportedOperationException ignored) {
      // Windows is not a supported production runtime; Git verification still fails closed if an
      // executable bit is material to the generation.
    } catch (IOException failure) {
      throw new IllegalStateException("WORKSPACE_GENERATION_ARCHIVE_MODE_RESTORE_FAILED", failure);
    }
  }

  private static void requireHash(String value, String code) {
    if (value == null || !value.matches("[0-9a-f]{40,64}"))
      throw new IllegalArgumentException(code);
  }

  private static String bounded(String value) {
    if (value == null) return "";
    return value.length() <= 2000 ? value : value.substring(0, 2000);
  }

  private static void deleteQuietly(Path path) {
    if (path == null) return;
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
    }
  }

  private static void deleteTreeQuietly(Path root) {
    if (root == null || !Files.exists(root)) return;
    try (var paths = Files.walk(root)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(WorkspaceGenerationArchiveService::deleteQuietly);
    } catch (IOException ignored) {
    }
  }

  public record Manifest(int version, String generation, List<RepositoryManifest> repositories) {}

  public record RepositoryManifest(int index, String path, String head, String tree) {}
}
