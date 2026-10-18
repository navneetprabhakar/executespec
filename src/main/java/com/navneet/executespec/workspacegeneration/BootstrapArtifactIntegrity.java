package com.navneet.executespec.workspacegeneration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.apache.commons.codec.digest.DigestUtils;

/** Verifies frozen server verifier files without traversing repository symlinks. */
final class BootstrapArtifactIntegrity {
  private BootstrapArtifactIntegrity() {}

  static boolean matches(Path workspaceRoot, TaskAcceptanceContract contract) {
    Path root = workspaceRoot.toAbsolutePath().normalize();
    for (Map.Entry<String, String> artifact :
        contract.immutableBootstrapArtifactDigests().entrySet()) {
      try {
        Path path = root.resolve(artifact.getKey()).normalize();
        if (!path.startsWith(root)
            || !Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(path)
            || hasSymlinkComponent(root, path)
            || !artifact.getValue().equals(DigestUtils.sha256Hex(Files.readAllBytes(path)))) {
          return false;
        }
      } catch (Exception failure) {
        return false;
      }
    }
    return true;
  }

  private static boolean hasSymlinkComponent(Path root, Path path) {
    Path current = root;
    for (Path segment : root.relativize(path)) {
      current = current.resolve(segment);
      if (Files.isSymbolicLink(current)) return true;
    }
    return false;
  }
}
