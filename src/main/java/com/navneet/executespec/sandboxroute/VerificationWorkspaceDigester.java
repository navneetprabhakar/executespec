package com.navneet.executespec.sandboxroute;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.apache.commons.codec.digest.DigestUtils;

/** Byte-, path-, type-, mode- and symlink-sensitive identity for a disposable verification tree. */
final class VerificationWorkspaceDigester {

  private static final int MAX_ENTRIES = 200_000;
  private static final long MAX_BYTES = 4L * 1024 * 1024 * 1024;

  private VerificationWorkspaceDigester() {}

  static String digest(Path root) {
    Path normalized = root.toAbsolutePath().normalize();
    long bytes = 0L;
    int entries = 0;
    MessageDigest digest = sha256();
    try (var paths = Files.walk(normalized)) {
      List<Path> ordered = paths.sorted().toList();
      for (Path path : ordered) {
        String relative = normalized.relativize(path).toString().replace('\\', '/');
        if (relative.isEmpty() || gitMetadata(relative)) continue;
        entries++;
        if (entries > MAX_ENTRIES) {
          throw new IllegalStateException("VERIFICATION_COMMAND_SNAPSHOT_ENTRY_LIMIT_EXCEEDED");
        }
        String evidence;
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
          evidence = "D\t" + relative;
        } else if (Files.isSymbolicLink(path)) {
          String target = Files.readSymbolicLink(path).toString();
          bytes = Math.addExact(bytes, target.getBytes(StandardCharsets.UTF_8).length);
          evidence = "L\t" + relative + "\t" + DigestUtils.sha256Hex(target);
        } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
          long size = Files.size(path);
          bytes = Math.addExact(bytes, size);
          try (var input = Files.newInputStream(path)) {
            evidence =
                "F\t"
                    + relative
                    + "\t"
                    + size
                    + "\t"
                    + mode(path)
                    + "\t"
                    + DigestUtils.sha256Hex(input);
          }
        } else {
          evidence = "O\t" + relative;
        }
        if (bytes > MAX_BYTES) {
          throw new IllegalStateException("VERIFICATION_COMMAND_SNAPSHOT_SIZE_LIMIT_EXCEEDED");
        }
        digest.update(evidence.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException | ArithmeticException failure) {
      throw new IllegalStateException("VERIFICATION_COMMAND_SNAPSHOT_FAILED", failure);
    }
  }

  private static String mode(Path path) {
    try {
      return Files.getPosixFilePermissions(path).contains(PosixFilePermission.OWNER_EXECUTE)
          ? "100755"
          : "100644";
    } catch (IOException | UnsupportedOperationException ignored) {
      return Files.isExecutable(path) ? "100755" : "100644";
    }
  }

  private static boolean gitMetadata(String relative) {
    return relative.equals(".git")
        || relative.startsWith(".git/")
        || relative.contains("/.git/")
        || relative.endsWith("/.git");
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
