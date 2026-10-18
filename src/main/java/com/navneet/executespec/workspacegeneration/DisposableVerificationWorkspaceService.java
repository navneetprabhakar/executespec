package com.navneet.executespec.workspacegeneration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.sandboxroute.SandboxCommandRoutingService;
import com.navneet.executespec.sandboxroute.VerificationSandboxAuthority;
import com.navneet.executespec.tool.ExecutionContext;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Exact-generation disposable boundary for every non-worker BUILD evidence command. */
@Service
@RequiredArgsConstructor
public class DisposableVerificationWorkspaceService {

  static final long MAX_SOURCE_BYTES = 4L * 1024 * 1024 * 1024;
  static final int MAX_SOURCE_ENTRIES = 200_000;
  static final long MIN_FREE_BYTES_AFTER_COPY = 1024L * 1024 * 1024;
  private static final Set<PosixFilePermission> OWNER_ONLY =
      PosixFilePermissions.fromString("rwx------");

  private final CorrectionWorkspaceTransaction generations;
  private final SandboxCommandRoutingService sandboxRouting;

  public <T> Execution<T> execute(
      ExecutionContext canonicalContext,
      WorkspaceGenerationDispatchAuthority.Phase phase,
      Function<ExecutionContext, T> command) {
    return executeBound(
        canonicalContext, () -> canonicalContext.assertWorkspaceGenerationDispatch(phase), command);
  }

  /** Run-level accepted-frontier sibling used after task attempts have terminalized. */
  public <T> Execution<T> executeAccepted(
      ExecutionContext canonicalContext,
      Runnable acceptedFrontierFence,
      Function<ExecutionContext, T> command) {
    if (acceptedFrontierFence == null) {
      throw new IllegalArgumentException("Accepted-frontier fence is required");
    }
    return executeBound(canonicalContext, acceptedFrontierFence, command);
  }

  private <T> Execution<T> executeBound(
      ExecutionContext canonicalContext,
      Runnable authorityFence,
      Function<ExecutionContext, T> command) {
    if (canonicalContext == null || authorityFence == null || command == null) {
      throw new IllegalArgumentException("Disposable verification inputs are required");
    }
    authorityFence.run();
    Path canonical = realDirectory(canonicalContext.workspaceRoot());
    String canonicalBefore = generations.captureGeneration(canonical);
    SourceShape source = inspectSource(canonical);
    VerificationSandboxAuthority sandbox = null;
    Path disposable = null;
    RuntimeException primaryFailure = null;
    Instant started = Instant.now();
    try {
      sandbox =
          sandboxRouting.openVerification(
              canonicalContext.runId(),
              canonicalContext.workspaceGenerationDispatchAuthority() == null
                  ? null
                  : canonicalContext.workspaceGenerationDispatchAuthority().attemptId(),
              canonicalBefore,
              WorkspaceGenerationDispatchAuthority.Phase.VERIFICATION);
      disposable = sandbox.workspaceRoot();
      assertCapacity(disposable, source.bytes());
      setOwnerOnly(disposable);
      copyTree(canonical, disposable);
      String disposableGeneration = generations.captureGeneration(disposable);
      if (!canonicalBefore.equals(disposableGeneration)) {
        throw new DisposableVerificationException(
            FailureKind.MATERIALIZATION_MISMATCH, "DISPOSABLE_VERIFICATION_GENERATION_MISMATCH");
      }
      sandboxRouting.activateVerification(sandbox, disposableGeneration);
      FileSnapshot before = snapshot(disposable);
      T value =
          command.apply(
              canonicalContext
                  .withWorkspaceRoot(disposable)
                  .withVerificationSandboxAuthority(sandbox));
      sandbox.assertLive();
      FileSnapshot after = snapshot(disposable);
      authorityFence.run();
      String canonicalAfter = generations.captureGeneration(canonical);
      if (!canonicalBefore.equals(canonicalAfter)) {
        throw new DisposableVerificationException(
            FailureKind.CANONICAL_MUTATION,
            "CANONICAL_WORKSPACE_CHANGED_DURING_DISPOSABLE_VERIFICATION");
      }
      return new Execution<>(
          value,
          canonicalBefore,
          disposableGeneration,
          source.entries(),
          source.bytes(),
          before.digest(),
          after.digest(),
          before.digest().equals(after.digest()) ? 0 : changedEntries(before, after),
          Duration.between(started, Instant.now()),
          true,
          sandboxRouting.verificationCommandReceiptIds(sandbox));
    } catch (DisposableVerificationException failure) {
      primaryFailure = failure;
      throw failure;
    } catch (SecurityException failure) {
      primaryFailure =
          new DisposableVerificationException(
              FailureKind.POLICY_DENIED, "DISPOSABLE_VERIFICATION_PATH_POLICY_DENIED", failure);
      throw primaryFailure;
    } catch (IOException failure) {
      primaryFailure =
          new DisposableVerificationException(
              FailureKind.MATERIALIZATION_FAILED,
              "DISPOSABLE_VERIFICATION_MATERIALIZATION_FAILED",
              failure);
      throw primaryFailure;
    } catch (SandboxCommandRoutingService.VerificationSandboxException failure) {
      primaryFailure =
          new DisposableVerificationException(
              FailureKind.POLICY_DENIED, failure.getMessage(), failure);
      throw primaryFailure;
    } catch (RuntimeException failure) {
      primaryFailure = failure;
      throw failure;
    } finally {
      RuntimeException closeFailure = null;
      if (sandbox != null) {
        try {
          sandboxRouting.closeVerification(sandbox);
        } catch (RuntimeException failure) {
          closeFailure = failure;
        }
      }
      // A runtime that failed to close may still have this directory mounted. Preserve it for the
      // durable sandbox reconciler/janitor; deleting underneath a live mount would destroy the
      // evidence needed to diagnose and safely settle that runtime.
      if (disposable != null && closeFailure == null) {
        try {
          deleteTree(disposable);
        } catch (RuntimeException failure) {
          if (closeFailure != null) closeFailure.addSuppressed(failure);
          else closeFailure = failure;
        }
      }
      if (closeFailure != null) {
        if (primaryFailure != null) {
          primaryFailure.addSuppressed(closeFailure);
        } else {
          throw new DisposableVerificationException(
              FailureKind.CLEANUP_FAILED, "DISPOSABLE_VERIFICATION_CLEANUP_FAILED", closeFailure);
        }
      }
    }
  }

  private static SourceShape inspectSource(Path source) {
    long entries = 0;
    long bytes = 0;
    try (var paths = Files.walk(source)) {
      for (Path path : paths.toList()) {
        entries++;
        if (entries > MAX_SOURCE_ENTRIES) {
          throw new DisposableVerificationException(
              FailureKind.CAPACITY_UNAVAILABLE, "DISPOSABLE_VERIFICATION_ENTRY_LIMIT_EXCEEDED");
        }
        if (Files.isSymbolicLink(path)) {
          requireSafeSymlink(source, path);
          bytes = Math.addExact(bytes, Files.readSymbolicLink(path).toString().length());
          continue;
        }
        if (path.getFileName() != null
            && ".git".equals(path.getFileName().toString())
            && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
          throw new DisposableVerificationException(
              FailureKind.POLICY_DENIED, "DISPOSABLE_VERIFICATION_LINKED_WORKTREE_UNSUPPORTED");
        }
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
          bytes = Math.addExact(bytes, Files.size(path));
          if (bytes > MAX_SOURCE_BYTES) {
            throw new DisposableVerificationException(
                FailureKind.CAPACITY_UNAVAILABLE, "DISPOSABLE_VERIFICATION_SIZE_LIMIT_EXCEEDED");
          }
        }
      }
    } catch (DisposableVerificationException failure) {
      throw failure;
    } catch (IOException | ArithmeticException failure) {
      throw new DisposableVerificationException(
          FailureKind.MATERIALIZATION_FAILED,
          "DISPOSABLE_VERIFICATION_SOURCE_SCAN_FAILED",
          failure);
    }
    return new SourceShape((int) entries, bytes);
  }

  private static void assertCapacity(Path base, long sourceBytes) {
    try {
      Path probe = Files.exists(base) ? base : base.getParent();
      if (probe == null) {
        throw new IOException("verification base has no existing parent");
      }
      FileStore store = Files.getFileStore(probe);
      long required = Math.addExact(sourceBytes, MIN_FREE_BYTES_AFTER_COPY);
      if (store.getUsableSpace() < required) {
        throw new DisposableVerificationException(
            FailureKind.CAPACITY_UNAVAILABLE, "DISPOSABLE_VERIFICATION_DISK_UNAVAILABLE");
      }
    } catch (DisposableVerificationException failure) {
      throw failure;
    } catch (IOException | ArithmeticException failure) {
      throw new DisposableVerificationException(
          FailureKind.CAPACITY_UNAVAILABLE, "DISPOSABLE_VERIFICATION_CAPACITY_UNKNOWN", failure);
    }
  }

  private static void copyTree(Path source, Path target) throws IOException {
    Files.walkFileTree(
        source,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            if (attrs.isSymbolicLink()) throw new SecurityException("symlink directory");
            Path relative = source.relativize(dir);
            Path destination = target.resolve(relative).normalize();
            requireTarget(target, destination);
            Files.createDirectories(destination);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Path destination = target.resolve(source.relativize(file)).normalize();
            requireTarget(target, destination);
            if (attrs.isSymbolicLink() || Files.isSymbolicLink(file)) {
              requireSafeSymlink(source, file);
              Files.deleteIfExists(destination);
              Files.createSymbolicLink(destination, Files.readSymbolicLink(file));
              return FileVisitResult.CONTINUE;
            }
            if (!attrs.isRegularFile()) {
              throw new SecurityException("only regular files are materializable");
            }
            Files.copy(
                file,
                destination,
                java.nio.file.StandardCopyOption.COPY_ATTRIBUTES,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                LinkOption.NOFOLLOW_LINKS);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static FileSnapshot snapshot(Path root) {
    List<String> entries = new ArrayList<>();
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted().toList()) {
        String relative = posix(root.relativize(path));
        if (relative.isEmpty() || isGitMetadata(relative)) continue;
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
          entries.add("D\t" + relative);
        } else if (Files.isSymbolicLink(path)) {
          entries.add(
              "L\t"
                  + relative
                  + "\t"
                  + DigestUtils.sha256Hex(Files.readSymbolicLink(path).toString()));
        } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
          entries.add("F\t" + relative + "\t" + Files.size(path) + "\t" + fileSha256(path));
        } else {
          entries.add("O\t" + relative);
        }
        if (entries.size() > MAX_SOURCE_ENTRIES) {
          throw new DisposableVerificationException(
              FailureKind.CAPACITY_UNAVAILABLE, "DISPOSABLE_VERIFICATION_SNAPSHOT_LIMIT_EXCEEDED");
        }
      }
    } catch (DisposableVerificationException failure) {
      throw failure;
    } catch (IOException failure) {
      throw new DisposableVerificationException(
          FailureKind.MALFORMED_EVIDENCE, "DISPOSABLE_VERIFICATION_SNAPSHOT_FAILED", failure);
    }
    return new FileSnapshot(List.copyOf(entries), sha256(String.join("\n", entries)));
  }

  private static String fileSha256(Path path) throws IOException {
    try (var input = Files.newInputStream(path)) {
      return DigestUtils.sha256Hex(input);
    }
  }

  private static boolean isGitMetadata(String relative) {
    return relative.equals(".git")
        || relative.startsWith(".git/")
        || relative.contains("/.git/")
        || relative.endsWith("/.git");
  }

  private static void requireSafeSymlink(Path root, Path link) {
    try {
      if (link.getFileName() != null && ".git".equals(link.getFileName().toString())) {
        throw new DisposableVerificationException(
            FailureKind.POLICY_DENIED, "DISPOSABLE_VERIFICATION_GIT_SYMLINK_REFUSED");
      }
      Path target = Files.readSymbolicLink(link);
      if (target.isAbsolute()) {
        throw new DisposableVerificationException(
            FailureKind.POLICY_DENIED, "DISPOSABLE_VERIFICATION_ABSOLUTE_SYMLINK_REFUSED");
      }
      Path parent = link.getParent();
      Path resolved = (parent == null ? root : parent).resolve(target).normalize();
      if (!resolved.startsWith(root.toAbsolutePath().normalize())) {
        throw new DisposableVerificationException(
            FailureKind.POLICY_DENIED, "DISPOSABLE_VERIFICATION_ESCAPING_SYMLINK_REFUSED");
      }
    } catch (IOException failure) {
      throw new DisposableVerificationException(
          FailureKind.MATERIALIZATION_FAILED,
          "DISPOSABLE_VERIFICATION_SYMLINK_INSPECTION_FAILED",
          failure);
    }
  }

  private static int changedEntries(FileSnapshot before, FileSnapshot after) {
    java.util.Set<String> left = new java.util.HashSet<>(before.entries());
    java.util.Set<String> right = new java.util.HashSet<>(after.entries());
    java.util.Set<String> union = new java.util.HashSet<>(left);
    union.addAll(right);
    java.util.Set<String> intersection = new java.util.HashSet<>(left);
    intersection.retainAll(right);
    union.removeAll(intersection);
    return union.size();
  }

  private static void deleteTree(Path root) {
    try {
      if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
      try (var paths = Files.walk(root)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    } catch (IOException failure) {
      throw new DisposableVerificationException(
          FailureKind.CLEANUP_FAILED, "DISPOSABLE_VERIFICATION_CLEANUP_FAILED", failure);
    }
  }

  private static Path realDirectory(Path value) {
    try {
      Path real = value.toRealPath();
      if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("not a directory");
      }
      return real;
    } catch (IOException failure) {
      throw new DisposableVerificationException(
          FailureKind.MATERIALIZATION_FAILED, "DISPOSABLE_VERIFICATION_ROOT_INVALID", failure);
    }
  }

  private static void requireTarget(Path root, Path candidate) {
    if (!candidate.startsWith(root)) throw new SecurityException("copy target escapes root");
  }

  private static void setOwnerOnly(Path path) {
    try {
      Files.setPosixFilePermissions(path, OWNER_ONLY);
    } catch (UnsupportedOperationException ignored) {
      // Windows/non-POSIX providers rely on the process-private workspace root ACL.
    } catch (IOException failure) {
      throw new DisposableVerificationException(
          FailureKind.POLICY_DENIED, "DISPOSABLE_VERIFICATION_PERMISSIONS_FAILED", failure);
    }
  }

  private static String posix(Path value) {
    return value.toString().replace('\\', '/');
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public enum FailureKind {
    CAPACITY_UNAVAILABLE,
    POLICY_DENIED,
    MATERIALIZATION_FAILED,
    MATERIALIZATION_MISMATCH,
    CANONICAL_MUTATION,
    MALFORMED_EVIDENCE,
    CLEANUP_FAILED
  }

  public static final class DisposableVerificationException extends RuntimeException {
    private final FailureKind kind;

    DisposableVerificationException(FailureKind kind, String message) {
      super(message);
      this.kind = kind;
    }

    DisposableVerificationException(FailureKind kind, String message, Throwable cause) {
      super(message, cause);
      this.kind = kind;
    }

    public FailureKind kind() {
      return kind;
    }
  }

  private record SourceShape(int entries, long bytes) {}

  private record FileSnapshot(List<String> entries, String digest) {}

  public record Execution<T>(
      T value,
      String canonicalGenerationBefore,
      String disposableGenerationBefore,
      int sourceEntries,
      long sourceBytes,
      String disposableSnapshotBeforeSha256,
      String disposableSnapshotAfterSha256,
      int sideEffectEntryCount,
      Duration duration,
      boolean cleanupRequired,
      List<UUID> commandReceiptIds) {

    public Execution(
        T value,
        String canonicalGenerationBefore,
        String disposableGenerationBefore,
        int sourceEntries,
        long sourceBytes,
        String disposableSnapshotBeforeSha256,
        String disposableSnapshotAfterSha256,
        int sideEffectEntryCount,
        Duration duration,
        boolean cleanupRequired) {
      this(
          value,
          canonicalGenerationBefore,
          disposableGenerationBefore,
          sourceEntries,
          sourceBytes,
          disposableSnapshotBeforeSha256,
          disposableSnapshotAfterSha256,
          sideEffectEntryCount,
          duration,
          cleanupRequired,
          List.of());
    }
  }
}
